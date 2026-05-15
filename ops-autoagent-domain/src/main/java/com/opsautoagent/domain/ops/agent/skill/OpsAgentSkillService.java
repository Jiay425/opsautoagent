package com.opsautoagent.domain.ops.agent.skill;

import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.entity.RootCauseCandidateEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
public class OpsAgentSkillService {

    @Value("${ops.agent.skill.enabled:true}")
    private boolean skillEnabled;

    @Value("${ops.agent.skill.base-path:docs/dev-ops/skills}")
    private String basePath;

    public List<OpsAgentSkill> match(OpsAlertEventEntity alertEvent, IncidentCommandEntity command, int topK) {
        String text = String.join("\n",
                value(alertEvent == null ? null : alertEvent.getAlertRule()),
                value(alertEvent == null ? null : alertEvent.getLabelsJson()),
                value(alertEvent == null ? null : alertEvent.getAnnotationsJson()),
                value(command == null ? null : command.getServiceName()),
                value(command == null ? null : command.getProblem()));
        return matchText(text, topK);
    }

    public List<OpsAgentSkill> match(IncidentCommandEntity command,
                                     List<RootCauseCandidateEntity> rootCauseCandidates,
                                     int topK) {
        StringBuilder text = new StringBuilder();
        text.append(value(command == null ? null : command.getServiceName())).append('\n')
                .append(value(command == null ? null : command.getProblem())).append('\n');
        if (rootCauseCandidates != null) {
            for (RootCauseCandidateEntity candidate : rootCauseCandidates) {
                text.append(value(candidate.getCategory())).append('\n')
                        .append(value(candidate.getCause())).append('\n')
                        .append(value(candidate.getReasoning())).append('\n');
            }
        }
        return matchText(text.toString(), topK);
    }

    public List<RunbookMatchEntity> toRunbookMatches(List<OpsAgentSkill> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        List<RunbookMatchEntity> matches = new ArrayList<>();
        for (OpsAgentSkill skill : skills) {
            matches.add(RunbookMatchEntity.builder()
                    .runbookId("skill:" + skill.getSkillId())
                    .title("[Skill] " + skill.getName())
                    .category(skill.getCategory())
                    .score(skill.getScore())
                    .path(skill.getRunbookPath())
                    .summary(buildSkillSummary(skill))
                    .content(abbreviate(JSON.toJSONString(skill), 2400))
                    .build());
        }
        return matches;
    }

    public List<String> recommendedTools(List<OpsAgentSkill> skills) {
        Set<String> tools = new LinkedHashSet<>();
        if (skills != null) {
            for (OpsAgentSkill skill : skills) {
                if (skill.getRecommendedTools() != null) {
                    tools.addAll(skill.getRecommendedTools());
                }
            }
        }
        return new ArrayList<>(tools);
    }

    private List<OpsAgentSkill> matchText(String text, int topK) {
        if (!skillEnabled) {
            return List.of();
        }
        List<OpsAgentSkill> skills = loadSkills();
        if (skills.isEmpty()) {
            return List.of();
        }
        String normalizedText = normalize(text);
        return skills.stream()
                .map(skill -> score(skill, normalizedText))
                .filter(skill -> skill.getScore() != null && skill.getScore() > 0)
                .sorted(Comparator.comparing(OpsAgentSkill::getScore).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private OpsAgentSkill score(OpsAgentSkill skill, String normalizedText) {
        int score = 0;
        score += scoreList(skill.getMatchedAlertRules(), normalizedText, 10);
        score += scoreList(skill.getSymptoms(), normalizedText, 6);
        score += scoreList(skill.getLogPatterns(), normalizedText, 7);
        score += scoreList(skill.getTracePatterns(), normalizedText, 7);
        score += scoreList(skill.getKeyMetrics(), normalizedText, 5);
        skill.setScore(Math.min(score, 100));
        return skill;
    }

    private int scoreList(List<String> values, String normalizedText, int weight) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String value : values) {
            String normalizedValue = normalize(value);
            if (!normalizedValue.isEmpty() && normalizedText.contains(normalizedValue)) {
                score += normalizedValue.length() > 4 ? weight : Math.max(2, weight / 2);
            }
        }
        return score;
    }

    private List<OpsAgentSkill> loadSkills() {
        Path skillPath = Path.of(basePath);
        if (!Files.exists(skillPath) || !Files.isDirectory(skillPath)) {
            log.warn("Ops skill path does not exist: {}", skillPath.toAbsolutePath());
            return List.of();
        }
        List<OpsAgentSkill> skills = new ArrayList<>();
        try (Stream<Path> files = Files.list(skillPath)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !"SKILL_TEMPLATE.md".equalsIgnoreCase(path.getFileName().toString()))
                    .forEach(path -> readSkill(path, skills));
        } catch (IOException e) {
            log.warn("Load ops skills failed. path={}", skillPath.toAbsolutePath(), e);
        }
        return skills;
    }

    private void readSkill(Path path, List<OpsAgentSkill> skills) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, String> metadata = parseFrontMatter(content);
            String skillId = metadata.getOrDefault("skillId", stripExtension(path.getFileName().toString()));
            skills.add(OpsAgentSkill.builder()
                    .skillId(skillId)
                    .name(metadata.getOrDefault("name", skillId))
                    .category(metadata.getOrDefault("category", "general"))
                    .matchedAlertRules(list(metadata.get("matchedAlertRules")))
                    .symptoms(list(metadata.get("symptoms")))
                    .recommendedTools(list(metadata.get("recommendedTools")))
                    .keyMetrics(list(metadata.get("keyMetrics")))
                    .logPatterns(list(metadata.get("logPatterns")))
                    .tracePatterns(list(metadata.get("tracePatterns")))
                    .rootCauseRules(list(metadata.get("rootCauseRules")))
                    .temporaryFixes(list(metadata.get("temporaryFixes")))
                    .longTermFixes(list(metadata.get("longTermFixes")))
                    .runbookPath(metadata.get("runbookPath"))
                    .content(abbreviate(content, 4000))
                    .score(0)
                    .build());
        } catch (IOException e) {
            log.warn("Read ops skill failed. path={}", path.toAbsolutePath(), e);
        }
    }

    private Map<String, String> parseFrontMatter(String content) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (content == null || !content.startsWith("---")) {
            return metadata;
        }
        String[] lines = content.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if ("---".equals(line.trim())) {
                break;
            }
            int index = line.indexOf(':');
            if (index <= 0) {
                continue;
            }
            metadata.put(line.substring(0, index).trim(), line.substring(index + 1).trim());
        }
        return metadata;
    }

    private List<String> list(String value) {
        if (value == null || value.trim().isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private String buildSkillSummary(OpsAgentSkill skill) {
        return "skillId=" + skill.getSkillId()
                + ", category=" + skill.getCategory()
                + ", recommendedTools=" + skill.getRecommendedTools()
                + ", temporaryFixes=" + skill.getTemporaryFixes()
                + ", longTermFixes=" + skill.getLongTermFixes();
    }

    private String normalize(String value) {
        return value(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\p{IsHan}]+", " ")
                .trim();
    }

    private String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

}

