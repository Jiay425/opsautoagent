package com.opsautoagent.infrastructure.adapter.gateway.ops;

import com.opsautoagent.domain.ops.agent.governance.OpsToolProtocolResolver;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsRunbookGateway;
import com.opsautoagent.domain.ops.adapter.repository.IOpsGovernanceRepository;
import com.opsautoagent.domain.ops.model.entity.EvidenceSignalEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsToolCallLogEntity;
import com.opsautoagent.domain.ops.model.entity.RootCauseCandidateEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import com.opsautoagent.domain.ops.service.OpsSensitiveDataMasker;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Component
public class FileOpsRunbookGateway implements IOpsRunbookGateway {

    @Value("${ops.runbook.base-path:docs/dev-ops/runbook}")
    private String basePath;

    @Resource
    private IOpsGovernanceRepository governanceRepository;

    @Resource
    private OpsSensitiveDataMasker sensitiveDataMasker;

    @Override
    public List<RunbookMatchEntity> search(IncidentCommandEntity command,
                                           List<RootCauseCandidateEntity> rootCauseCandidates,
                                           int topK) {
        long start = System.currentTimeMillis();
        Path runbookPath = Path.of(basePath);
        if (!Files.exists(runbookPath) || !Files.isDirectory(runbookPath)) {
            log.warn("Ops runbook path does not exist: {}", runbookPath.toAbsolutePath());
            saveToolCallLog(command, runbookPath.toString(), "runbook path missing", List.of(),
                    System.currentTimeMillis() - start, false, "runbook path does not exist");
            return List.of();
        }

        Set<String> queryTokens = buildQueryTokens(command, rootCauseCandidates);
        List<RunbookMatchEntity> matches = new ArrayList<>();

        try (Stream<Path> files = Files.list(runbookPath)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> addMatch(path, queryTokens, matches));
        } catch (IOException e) {
            log.warn("Search ops runbooks failed. path={}", runbookPath.toAbsolutePath(), e);
            saveToolCallLog(command, runbookPath.toString(), queryTokens.toString(), List.of(),
                    System.currentTimeMillis() - start, false, e.getMessage());
            return List.of();
        }

        List<RunbookMatchEntity> result = matches.stream()
                .filter(match -> match.getScore() > 0)
                .sorted(Comparator.comparing(RunbookMatchEntity::getScore).reversed())
                .limit(Math.max(1, topK))
                .toList();
        saveToolCallLog(command, runbookPath.toString(), queryTokens.toString(), result,
                System.currentTimeMillis() - start, true, null);
        return result;
    }

    @Override
    public List<RunbookMatchEntity> searchByEvidenceSignals(IncidentCommandEntity command,
                                                            List<EvidenceSignalEntity> evidenceSignals,
                                                            int topK) {
        long start = System.currentTimeMillis();
        Path runbookPath = Path.of(basePath);
        if (!Files.exists(runbookPath) || !Files.isDirectory(runbookPath)) {
            log.warn("Ops runbook path does not exist: {}", runbookPath.toAbsolutePath());
            saveToolCallLog(command, runbookPath.toString(), "runbook path missing", List.of(),
                    System.currentTimeMillis() - start, false, "runbook path does not exist");
            return List.of();
        }

        Set<String> queryTokens = buildSignalQueryTokens(command, evidenceSignals);
        List<RunbookMatchEntity> matches = new ArrayList<>();

        try (Stream<Path> files = Files.list(runbookPath)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> addMatch(path, queryTokens, matches));
        } catch (IOException e) {
            log.warn("Search ops runbooks by evidence signals failed. path={}", runbookPath.toAbsolutePath(), e);
            saveToolCallLog(command, runbookPath.toString(), queryTokens.toString(), List.of(),
                    System.currentTimeMillis() - start, false, e.getMessage());
            return List.of();
        }

        List<RunbookMatchEntity> result = matches.stream()
                .filter(match -> match.getScore() > 0)
                .sorted(Comparator.comparing(RunbookMatchEntity::getScore).reversed())
                .limit(Math.max(1, topK))
                .toList();
        saveToolCallLog(command, runbookPath.toString(), queryTokens.toString(), result,
                System.currentTimeMillis() - start, true, null);
        return result;
    }

    private void addMatch(Path path, Set<String> queryTokens, List<RunbookMatchEntity> matches) {
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

            matches.add(RunbookMatchEntity.builder()
                    .runbookId(stripExtension(path.getFileName().toString()))
                    .title(extractTitle(content, path))
                    .category(resolveCategory(path.getFileName().toString(), content))
                    .score(Math.min(score, 100))
                    .path(path.toString())
                    .summary(extractSummary(content))
                    .content(abbreviate(content, 2400))
                    .build());
        } catch (IOException e) {
            log.warn("Read ops runbook failed. path={}", path.toAbsolutePath(), e);
        }
    }

    private Set<String> buildQueryTokens(IncidentCommandEntity command,
                                         List<RootCauseCandidateEntity> rootCauseCandidates) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, command.getServiceName());
        addTokens(tokens, command.getProblem());
        if (rootCauseCandidates != null) {
            rootCauseCandidates.forEach(candidate -> {
                addTokens(tokens, candidate.getCategory());
                addTokens(tokens, candidate.getCause());
                addTokens(tokens, candidate.getReasoning());
            });
        }
        return tokens;
    }

    private Set<String> buildSignalQueryTokens(IncidentCommandEntity command,
                                               List<EvidenceSignalEntity> evidenceSignals) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, command.getServiceName());
        addTokens(tokens, command.getProblem());
        if (evidenceSignals != null) {
            evidenceSignals.forEach(signal -> {
                addTokens(tokens, signal.getSource());
                addTokens(tokens, signal.getEvidenceType());
                addTokens(tokens, signal.getName());
                addTokens(tokens, signal.getStatus());
                addTokens(tokens, signal.getSummary());
                addTokens(tokens, signal.getRawEvidence());
            });
        }
        return tokens;
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
            String trimLine = line.trim();
            if (trimLine.isEmpty() || trimLine.startsWith("#") || "## keywords".equalsIgnoreCase(trimLine)) {
                continue;
            }
            summary.append(trimLine).append("\n");
            if (summary.length() > 500) {
                break;
            }
        }
        return abbreviate(summary.toString().trim(), 600);
    }

    private String resolveCategory(String fileName, String content) {
        String text = (fileName + "\n" + content).toLowerCase(Locale.ROOT);
        if (text.contains("hikari") || text.contains("jdbc") || text.contains("database")) {
            return "database";
        }
        if (text.contains("redis")) {
            return "redis";
        }
        if (text.contains("full gc") || text.contains("outofmemory")) {
            return "jvm";
        }
        if (text.contains("dubbo") || text.contains("rpc") || text.contains("downstream")) {
            return "downstream";
        }
        if (text.contains("mq") || text.contains("kafka") || text.contains("rocketmq")) {
            return "mq";
        }
        if (text.contains("500") || text.contains("exception")) {
            return "application";
        }
        return "general";
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

    private void saveToolCallLog(IncidentCommandEntity command,
                                 String target,
                                 String requestSummary,
                                 List<RunbookMatchEntity> responseSummary,
                                 long costMillis,
                                 boolean success,
                                 String errorMessage) {
        try {
            governanceRepository.saveToolCallLog(OpsToolCallLogEntity.builder()
                    .callId("tool-" + UUID.randomUUID())
                    .sessionId(command.getSessionId())
                    .diagnosisId(command.getDiagnosisId())
                    .toolName("runbook")
                    .logicalToolName(OpsToolProtocolResolver.logicalToolNameOf("runbook", target))
                    .protocol(OpsToolProtocolResolver.protocolOf("runbook", target))
                    .governanceDecision(success ? "SUCCESS" : "FAILED")
                    .target(abbreviate(mask(target), 256))
                    .requestSummary(abbreviate(mask(requestSummary), 6000))
                    .responseSummary(abbreviate(mask(JSON.toJSONString(responseSummary)), 6000))
                    .statusCode(null)
                    .costMillis(costMillis)
                    .success(Boolean.toString(success))
                    .errorMessage(abbreviate(mask(errorMessage), 2000))
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Save runbook tool call log failed. sessionId={}", command.getSessionId(), e);
        }
    }

    private String mask(String value) {
        return sensitiveDataMasker.mask(value);
    }

}

