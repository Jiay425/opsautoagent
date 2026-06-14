package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IncidentFixWorkingMemory {

    private String taskId;

    private String taskType;

    private String goal;

    private String repository;

    private String changeRef;

    private Map<String, Object> incidentSummary;

    private Map<String, Object> opsEvidence;

    private List<Object> codeHints;

    private Map<String, Object> codeLocalization;

    private Map<String, Object> engineeringKnowledge;

    private Map<String, Object> fixStrategy;

    private Map<String, Object> rootCauseAnalysis;

    private Map<String, Object> patchGeneration;

    private Map<String, Object> testVerification;

    private Map<String, Object> releaseRisk;

    private Map<String, Object> finalReview;

    private Map<String, Object> safetySummary;

    private List<Map<String, Object>> agentTrace;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static IncidentFixWorkingMemory initialize(EngineeringTaskEntity task) {
        Map<String, Object> incidentSummary = new LinkedHashMap<>();
        incidentSummary.put("taskType", task.getTaskType());
        incidentSummary.put("goal", task.getGoal());
        incidentSummary.put("sourceContext", compactSourceContext(task.getContext()));

        return IncidentFixWorkingMemory.builder()
                .taskId(task.getTaskId())
                .taskType(task.getTaskType())
                .goal(task.getGoal())
                .repository(task.getRepository())
                .changeRef(task.getChangeRef())
                .incidentSummary(incidentSummary)
                .opsEvidence(new LinkedHashMap<>())
                .codeHints(new ArrayList<>())
                .codeLocalization(new LinkedHashMap<>())
                .engineeringKnowledge(new LinkedHashMap<>())
                .fixStrategy(new LinkedHashMap<>())
                .rootCauseAnalysis(new LinkedHashMap<>())
                .patchGeneration(new LinkedHashMap<>())
                .testVerification(new LinkedHashMap<>())
                .releaseRisk(new LinkedHashMap<>())
                .finalReview(new LinkedHashMap<>())
                .safetySummary(new LinkedHashMap<>())
                .agentTrace(new ArrayList<>())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }

    private static Map<String, Object> compactSourceContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        context.forEach((key, value) -> {
            if ("repoBaselineSnapshot".equals(key) && value instanceof Map<?, ?> snapshot) {
                compact.put("repoBaselineSnapshotSummary", Map.of(
                        "fileCount", snapshot.size(),
                        "sampleFiles", snapshot.keySet().stream().limit(20).map(String::valueOf).toList()
                ));
            } else {
                compact.put(key, value);
            }
        });
        return compact;
    }

    public void recordSkillOutput(String skillId, Map<String, Object> rawOutput) {
        Map<String, Object> output = rawOutput == null ? Map.of() : new LinkedHashMap<>(rawOutput);
        switch (skillId) {
            case "ops_diagnosis" -> {
                opsEvidence = output;
                Object hints = output.get("codeHints");
                if (hints instanceof List<?> hintList) {
                    codeHints = new ArrayList<>(hintList);
                }
                updateSafetySummary("evidenceCoverage", output.get("evidenceCoverage"));
                updateSafetySummary("evidenceProvenance", output.get("evidenceProvenance"));
            }
            case "repo_understanding" -> {
                codeLocalization = output;
                fixStrategy = extractFixStrategy(output);
            }
            case "agent_loop_investigation" -> {
                codeLocalization = merge(codeLocalization, output);
                fixStrategy = merge(fixStrategy, extractFixStrategy(output));
                if (finalReview == null) {
                    finalReview = new LinkedHashMap<>();
                }
                finalReview.put("agentLoopInvestigation", output);
            }
            case "engineering_knowledge_rag" -> engineeringKnowledge = output;
            case "fix_strategy_router" -> fixStrategy = output;
            case "bug_fix" -> {
                patchGeneration = output;
                rootCauseAnalysis = extractRootCause(output);
                updateSafetySummary("patchSandbox", output.get("patchSandbox"));
                updateSafetySummary("patchQuality", output.get("patchQuality"));
                updateSafetySummary("patchDiffAnalysis", output.get("patchDiffAnalysis"));
            }
            case "test_verification" -> {
                testVerification = output;
                updateSafetySummary("testExecutionRepositoryPath", output.get("testExecutionRepositoryPath"));
                updateSafetySummary("testExecutionResults", output.get("testExecutionResults"));
            }
            case "release_risk", "release_risk_analysis" -> {
                releaseRisk = output;
                updateSafetySummary("releaseRisk", output.get("releaseRiskReport"));
            }
            default -> finalReview.put(skillId, output);
        }
        appendTrace(skillId, output);
        updateTime = LocalDateTime.now();
    }

    private Map<String, Object> extractRootCause(Map<String, Object> output) {
        Map<String, Object> rootCause = new LinkedHashMap<>();
        putIfPresent(rootCause, "rootCause", output.get("rootCause"));
        putIfPresent(rootCause, "confidence", output.get("confidence"));
        putIfPresent(rootCause, "reasoning", output.get("reasoning"));
        putIfPresent(rootCause, "targetFiles", output.get("targetFiles"));
        return rootCause;
    }

    private Map<String, Object> merge(Map<String, Object> current, Map<String, Object> output) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        if (output != null) {
            merged.putAll(output);
        }
        return merged;
    }

    private Map<String, Object> extractFixStrategy(Map<String, Object> output) {
        Map<String, Object> strategy = new LinkedHashMap<>();
        putIfPresent(strategy, "strategyType", output.get("strategyType"));
        putIfPresent(strategy, "shouldEnterCodeRepair", output.get("shouldEnterCodeRepair"));
        putIfPresent(strategy, "confidence", output.get("localizationConfidence"));
        putIfPresent(strategy, "reasoning", output.get("localizationReasoning"));
        putIfPresent(strategy, "missingEvidence", output.get("missingEvidence"));
        return strategy;
    }

    private void appendTrace(String skillId, Map<String, Object> output) {
        if (agentTrace == null) {
            agentTrace = new ArrayList<>();
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("agentOrSkill", skillId);
        trace.put("time", LocalDateTime.now().toString());
        putIfPresent(trace, "summary", output.get("summary"));
        putIfPresent(trace, "status", output.get("status"));
        putIfPresent(trace, "rootCause", output.get("rootCause"));
        putIfPresent(trace, "confidence", output.get("confidence"));
        putIfPresent(trace, "targetFiles", output.get("targetFiles"));
        putIfPresent(trace, "codeHints", output.get("codeHints"));
        putIfPresent(trace, "evidenceCoverage", output.get("evidenceCoverage"));
        putIfPresent(trace, "patchSandbox", output.get("patchSandbox"));
        putIfPresent(trace, "patchQuality", output.get("patchQuality"));
        putIfPresent(trace, "testExecutionRepositoryPath", output.get("testExecutionRepositoryPath"));
        putIfPresent(trace, "recommendedTests", output.get("recommendedTests"));
        putIfPresent(trace, "riskPoints", output.get("riskPoints"));
        agentTrace.add(trace);
    }

    private void updateSafetySummary(String key, Object value) {
        if (value == null) {
            return;
        }
        if (safetySummary == null) {
            safetySummary = new LinkedHashMap<>();
        }
        safetySummary.put(key, value);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

}
