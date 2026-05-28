package com.opsautoagent.domain.codeops.agent.knowledge;

import com.opsautoagent.domain.codeops.model.entity.EngineeringKnowledgeMatchEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.ReviewFindingEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
public class EngineeringKnowledgeSearchService {

    @Value("${codeops.knowledge.base-path:docs}")
    private String basePath;

    @Value("${codeops.knowledge.max-content-length:2400}")
    private int maxContentLength;

    public List<EngineeringKnowledgeMatchEntity> search(EngineeringTaskEntity task,
                                                        List<String> changedFiles,
                                                        List<ReviewFindingEntity> findings,
                                                        int topK) {
        Path knowledgePath = Path.of(basePath);
        if (!Files.exists(knowledgePath) || !Files.isDirectory(knowledgePath)) {
            log.warn("CodeOps knowledge path does not exist: {}", knowledgePath.toAbsolutePath());
            return List.of();
        }

        Set<String> queryTokens = buildTokens(task, changedFiles, findings);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        List<EngineeringKnowledgeMatchEntity> matches = new ArrayList<>();
        try (Stream<Path> files = Files.walk(knowledgePath, 8)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> addMatch(path, queryTokens, matches));
        } catch (IOException e) {
            log.warn("Search CodeOps knowledge failed. path={}", knowledgePath.toAbsolutePath(), e);
            return List.of();
        }

        return matches.stream()
                .filter(match -> match.getScore() != null && match.getScore() > 0)
                .sorted(Comparator.comparing(EngineeringKnowledgeMatchEntity::getScore).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private Set<String> buildTokens(EngineeringTaskEntity task,
                                    List<String> changedFiles,
                                    List<ReviewFindingEntity> findings) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, task == null ? null : task.getTaskType());
        addTokens(tokens, task == null ? null : task.getGoal());
        if (task != null && task.getFocusAreas() != null) {
            task.getFocusAreas().forEach(focus -> addTokens(tokens, focus));
        }
        if (changedFiles != null) {
            changedFiles.forEach(file -> {
                addTokens(tokens, file);
                addTokens(tokens, Path.of(file).getFileName().toString());
            });
        }
        if (findings != null) {
            findings.forEach(finding -> {
                addTokens(tokens, finding.getCategory());
                addTokens(tokens, finding.getTitle());
                addTokens(tokens, finding.getDetail());
            });
        }
        addDomainTokens(tokens);
        return tokens;
    }

    private void addDomainTokens(Set<String> tokens) {
        tokens.add("review");
        tokens.add("risk");
        tokens.add("runbook");
        tokens.add("transaction");
        tokens.add("cache");
        tokens.add("thread");
        tokens.add("timeout");
        tokens.add("test");
        tokens.add("发布");
        tokens.add("复盘");
        tokens.add("规范");
        tokens.add("测试");
    }

    private void addMatch(Path path, Set<String> queryTokens, List<EngineeringKnowledgeMatchEntity> matches) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String lowerContent = content.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String token : queryTokens) {
                if (lowerContent.contains(token)) {
                    score += token.length() > 4 ? 8 : 4;
                }
            }
            if (score <= 0) {
                return;
            }
            matches.add(EngineeringKnowledgeMatchEntity.builder()
                    .documentId(stripExtension(path.getFileName().toString()))
                    .title(extractTitle(content, path))
                    .category(resolveCategory(path, content))
                    .score(Math.min(score, 100))
                    .path(path.toString())
                    .summary(extractSummary(content))
                    .content(abbreviate(content, maxContentLength))
                    .build());
        } catch (IOException e) {
            log.warn("Read CodeOps knowledge failed. path={}", path.toAbsolutePath(), e);
        }
    }

    private void addTokens(Set<String> tokens, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\p{IsHan}]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
    }

    private String extractTitle(String content, Path path) {
        for (String line : content.split("\\R")) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return stripExtension(path.getFileName().toString());
    }

    private String extractSummary(String content) {
        StringBuilder summary = new StringBuilder();
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            summary.append(trimmed).append('\n');
            if (summary.length() > 600) {
                break;
            }
        }
        return abbreviate(summary.toString().trim(), 800);
    }

    private String resolveCategory(Path path, String content) {
        String text = (path.toString() + "\n" + content).toLowerCase(Locale.ROOT);
        if (text.contains("runbook")) {
            return "runbook";
        }
        if (text.contains("review") || text.contains("审查")) {
            return "review";
        }
        if (text.contains("复盘") || text.contains("postmortem")) {
            return "postmortem";
        }
        if (text.contains("发布") || text.contains("release")) {
            return "release";
        }
        if (text.contains("测试") || text.contains("test")) {
            return "test";
        }
        return "engineering_doc";
    }

    private String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

}
