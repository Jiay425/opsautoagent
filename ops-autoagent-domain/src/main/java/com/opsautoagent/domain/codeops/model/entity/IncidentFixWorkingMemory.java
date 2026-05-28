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

    private List<Map<String, Object>> agentTrace;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static IncidentFixWorkingMemory initialize(EngineeringTaskEntity task) {
        Map<String, Object> incidentSummary = new LinkedHashMap<>();
        incidentSummary.put("taskType", task.getTaskType());
        incidentSummary.put("goal", task.getGoal());
        incidentSummary.put("sourceContext", task.getContext() == null ? Map.of() : new LinkedHashMap<>(task.getContext()));

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
                .agentTrace(new ArrayList<>())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
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
            }
            case "repo_understanding" -> {
                codeLocalization = output;
                fixStrategy = extractFixStrategy(output);
            }
            case "engineering_knowledge_rag" -> engineeringKnowledge = output;
            case "fix_strategy_router" -> fixStrategy = output;
            case "bug_fix" -> {
                patchGeneration = output;
                rootCauseAnalysis = extractRootCause(output);
            }
            case "test_verification" -> testVerification = output;
            case "release_risk", "release_risk_analysis" -> releaseRisk = output;
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
        putIfPresent(trace, "recommendedTests", output.get("recommendedTests"));
        putIfPresent(trace, "riskPoints", output.get("riskPoints"));
        agentTrace.add(trace);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

}
