package com.opsautoagent.domain.codeops.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.api.dto.CodeOpsIncidentFixStageDTO;
import com.opsautoagent.api.dto.CodeOpsIncidentFixViewDTO;
import com.opsautoagent.api.dto.CodeOpsTaskTraceDTO;
import com.opsautoagent.api.dto.CodeOpsTaskTraceStepDTO;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskStepEntity;
import com.opsautoagent.domain.codeops.model.entity.IncidentFixWorkingMemory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EngineeringTaskTraceService {

    private static final List<StageDefinition> INCIDENT_FIX_STAGES = List.of(
            new StageDefinition("ops_evidence", "线上证据采集", List.of("ops_diagnosis")),
            new StageDefinition("code_localization", "代码定位", List.of("agent_loop_investigation", "repo_understanding")),
            new StageDefinition("knowledge_rag", "知识检索", List.of("engineering_knowledge_rag")),
            new StageDefinition("code_repair", "代码修复", List.of("bug_fix")),
            new StageDefinition("test_verification", "编译测试", List.of("test_verification")),
            new StageDefinition("release_risk", "发布风险", List.of("release_risk_analysis")),
            new StageDefinition("human_approval", "人工审批", List.of())
    );

    public CodeOpsTaskTraceDTO buildTrace(EngineeringTaskEntity task) {
        return CodeOpsTaskTraceDTO.builder()
                .taskId(task.getTaskId())
                .taskType(task.getTaskType())
                .goal(task.getGoal())
                .status(task.getStatus())
                .finalSummary(task.getFinalSummary())
                .stepCount(task.getSteps() == null ? 0 : task.getSteps().size())
                .usedToolCalls(task.getUsedToolCalls())
                .workingMemorySummary(buildWorkingMemorySummary(task))
                .timeline(task.getSteps() == null ? List.of() : task.getSteps().stream().map(this::toTraceStep).toList())
                .build();
    }

    public CodeOpsIncidentFixViewDTO buildIncidentFixView(EngineeringTaskEntity task, Map<String, Object> approval) {
        CodeOpsTaskTraceDTO trace = buildTrace(task);
        Map<String, Object> latestRaw = collectLatestRawOutputs(task);
        Map<String, Object> guardrails = task.getContext() == null ? Map.of() : mapValue(task.getContext().get("guardrailSummary"));
        Map<String, Object> safeApproval = approval == null ? Map.of() : approval;
        List<CodeOpsIncidentFixStageDTO> stages = buildIncidentFixStages(task, safeApproval);
        return CodeOpsIncidentFixViewDTO.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus())
                .currentStage(resolveCurrentStage(task, stages, safeApproval))
                .progressPercent(resolveProgressPercent(stages, task.getStatus()))
                .requiresApproval(!safeApproval.isEmpty() && "PENDING".equalsIgnoreCase(stringValue(safeApproval.get("status"))))
                .approvalStatus(safeApproval.isEmpty() ? "NOT_REQUIRED_OR_NOT_SUBMITTED" : stringValue(safeApproval.get("status")))
                .goal(task.getGoal())
                .repository(task.getRepository())
                .finalSummary(task.getFinalSummary())
                .incident(buildIncidentSummary(task))
                .guardrails(guardrails)
                .approval(safeApproval)
                .artifacts(buildIncidentArtifacts(latestRaw, guardrails))
                .stages(stages)
                .trace(trace)
                .build();
    }

    private Map<String, Object> buildWorkingMemorySummary(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        Object memory = task.getContext().get("incidentFixWorkingMemory");
        if (!(memory instanceof IncidentFixWorkingMemory workingMemory)) {
            putRuntimeSummary(summary, task.getContext());
            return summary;
        }
        putIfPresent(summary, "incidentSummary", workingMemory.getIncidentSummary());
        putIfPresent(summary, "codeHints", workingMemory.getCodeHints());
        putIfPresent(summary, "codeLocalization", compactMap(workingMemory.getCodeLocalization(),
                "localizationConfidence", "targetFiles", "targetMethods", "suspiciousLocations", "missingEvidence",
                "evidenceGraphSummary", "evidenceGraphRankedCodeNodes",
                "finalAnswer", "turns", "trace", "recommendedTests", "strategyType"));
        putIfPresent(summary, "rootCauseAnalysis", workingMemory.getRootCauseAnalysis());
        putIfPresent(summary, "patchGeneration", compactMap(workingMemory.getPatchGeneration(),
                "rootCause", "confidence", "targetFiles", "llmGenerated", "patchValidation"));
        putIfPresent(summary, "testVerification", compactMap(workingMemory.getTestVerification(),
                "recommendedTests", "coverageGaps", "mavenCommands", "testExecutionAsync",
                "testExecutionResults", "queuedBackgroundTasks", "backgroundToolTasks", "taskNotifications"));
        putIfPresent(summary, "releaseRisk", compactMap(workingMemory.getReleaseRisk(),
                "releaseRiskReport", "humanApprovalPoints", "releaseRiskReasoning",
                "manualTakeoverRequired", "autoPatchBlockedReason", "verificationBlockedReason",
                "blockedAutomationSummary"));
        putIfPresent(summary, "agentTrace", workingMemory.getAgentTrace());
        putRuntimeSummary(summary, task.getContext());
        return summary;
    }

    private void putRuntimeSummary(Map<String, Object> summary, Map<String, Object> context) {
        putIfPresent(summary, "taskDagNodes", context.get("taskDagNodes"));
        putIfPresent(summary, "backgroundToolTasks", context.get("backgroundToolTasks"));
        putIfPresent(summary, "taskNotifications", context.get("taskNotifications"));
        putIfPresent(summary, "agentRuntimeTrace", context.get("agentRuntimeTrace"));
        putIfPresent(summary, "toolRuntimeTrace", context.get("toolRuntimeTrace"));
    }

    private Map<String, Object> compactMap(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : keys) {
            putIfPresent(compact, key, source.get(key));
        }
        return compact;
    }

    private CodeOpsTaskTraceStepDTO toTraceStep(EngineeringTaskStepEntity step) {
        Map<String, Object> rawEvidence = parseRawEvidence(step.getRawEvidenceJson());
        return CodeOpsTaskTraceStepDTO.builder()
                .stepNo(step.getStepNo())
                .skillId(step.getSelectedSkill())
                .decision(step.getDecision())
                .status(step.getStatus())
                .reason(step.getReason())
                .summary(step.getResultSummary())
                .evidence(step.getExpectedEvidence())
                .phase(stringValue(rawEvidence.get("phase")))
                .highlights(buildHighlights(step.getSelectedSkill(), rawEvidence))
                .rawEvidence(rawEvidence)
                .build();
    }

    private List<CodeOpsIncidentFixStageDTO> buildIncidentFixStages(EngineeringTaskEntity task, Map<String, Object> approval) {
        Map<String, EngineeringTaskStepEntity> latestSteps = latestStepBySkill(task);
        Map<String, Map<String, Object>> rawBySkill = rawOutputBySkill(task);
        return INCIDENT_FIX_STAGES.stream()
                .map(stage -> {
                    if ("human_approval".equals(stage.stageId())) {
                        return buildApprovalStage(approval);
                    }
                    EngineeringTaskStepEntity step = findStageStep(stage, latestSteps);
                    Map<String, Object> raw = findStageRaw(stage, rawBySkill);
                    return CodeOpsIncidentFixStageDTO.builder()
                            .stageId(stage.stageId())
                            .stageName(stage.stageName())
                            .skillIds(stage.skillIds())
                            .stepNo(step == null ? null : step.getStepNo())
                            .status(resolveStageStatus(step))
                            .summary(step == null ? "" : step.getResultSummary())
                            .evidence(step == null || step.getExpectedEvidence() == null ? List.of() : step.getExpectedEvidence())
                            .keyArtifacts(buildStageArtifacts(stage.stageId(), raw))
                            .build();
                })
                .toList();
    }

    private CodeOpsIncidentFixStageDTO buildApprovalStage(Map<String, Object> approval) {
        String approvalStatus = approval == null || approval.isEmpty()
                ? "NOT_REQUIRED"
                : stringValue(approval.get("status"));
        String status = switch (approvalStatus == null ? "" : approvalStatus.toUpperCase()) {
            case "PENDING" -> "WAITING_APPROVAL";
            case "APPROVED" -> "SUCCESS";
            case "REJECTED" -> "FAILED";
            default -> "SKIPPED";
        };
        return CodeOpsIncidentFixStageDTO.builder()
                .stageId("human_approval")
                .stageName("人工审批")
                .skillIds(List.of())
                .status(status)
                .summary(approval == null || approval.isEmpty()
                        ? "当前任务没有进入人工审批"
                        : "审批状态：" + approvalStatus)
                .evidence(approval == null || approval.isEmpty()
                        ? List.of()
                        : stringList(approval.get("approvalReasons")))
                .keyArtifacts(approval == null ? Map.of() : approval)
                .build();
    }

    private String resolveStageStatus(EngineeringTaskStepEntity step) {
        if (step == null) {
            return "PENDING";
        }
        String status = stringValue(step.getStatus());
        if ("SUCCESS".equalsIgnoreCase(status)) {
            return "SUCCESS";
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            return "FAILED";
        }
        if ("STOPPED".equalsIgnoreCase(status) || "SKIPPED".equalsIgnoreCase(status)) {
            return "SKIPPED";
        }
        return status == null || status.isBlank() ? "UNKNOWN" : status;
    }

    private String resolveCurrentStage(EngineeringTaskEntity task,
                                       List<CodeOpsIncidentFixStageDTO> stages,
                                       Map<String, Object> approval) {
        if (!approval.isEmpty() && "PENDING".equalsIgnoreCase(stringValue(approval.get("status")))) {
            return "human_approval";
        }
        for (CodeOpsIncidentFixStageDTO stage : stages) {
            if ("FAILED".equalsIgnoreCase(stage.getStatus())
                    || "WAITING_APPROVAL".equalsIgnoreCase(stage.getStatus())
                    || "PENDING".equalsIgnoreCase(stage.getStatus())) {
                return stage.getStageId();
            }
        }
        return "FAILED".equalsIgnoreCase(task.getStatus()) ? "failed" : "completed";
    }

    private Integer resolveProgressPercent(List<CodeOpsIncidentFixStageDTO> stages, String taskStatus) {
        if (stages == null || stages.isEmpty()) {
            return 0;
        }
        if ("COMPLETED".equalsIgnoreCase(taskStatus)) {
            return 100;
        }
        int finished = 0;
        for (CodeOpsIncidentFixStageDTO stage : stages) {
            String status = stage.getStatus();
            if ("SUCCESS".equalsIgnoreCase(status) || "SKIPPED".equalsIgnoreCase(status)) {
                finished++;
            }
        }
        return Math.min(99, Math.round(finished * 100F / stages.size()));
    }

    private Map<String, Object> buildIncidentSummary(EngineeringTaskEntity task) {
        Map<String, Object> context = task.getContext() == null ? Map.of() : task.getContext();
        Map<String, Object> incident = new LinkedHashMap<>();
        putIfPresent(incident, "source", context.get("source"));
        putIfPresent(incident, "eventId", context.get("eventId"));
        putIfPresent(incident, "opsDiagnosisId", context.get("opsDiagnosisId"));
        putIfPresent(incident, "serviceName", context.get("serviceName"));
        putIfPresent(incident, "alertRule", context.get("alertRule"));
        putIfPresent(incident, "severity", context.get("severity"));
        putIfPresent(incident, "traceId", context.get("traceId"));
        putIfPresent(incident, "startTime", context.get("startTime"));
        putIfPresent(incident, "endTime", context.get("endTime"));
        putIfPresent(incident, "evidenceMode", context.get("evidenceMode"));
        putIfPresent(incident, "fixtureFallbackAllowed", context.get("fixtureFallbackAllowed"));
        return incident;
    }

    private Map<String, Object> buildIncidentArtifacts(Map<String, Object> raw, Map<String, Object> guardrails) {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("evidence", pick(raw, "evidenceCoverage", "evidenceProvenance", "evidenceSources", "evidenceDetails"));
        artifacts.put("localization", pick(raw, "targetFiles", "targetMethods", "suspiciousLocations",
                "localizationConfidence", "missingEvidence", "evidenceGraphSummary",
                "evidenceGraphRankedCodeNodes", "evidenceGraph"));
        artifacts.put("patch", pick(raw, "patchGenerated", "llmGenerated", "patchApply", "patchScopeGuard", "patchSandbox", "patchQuality", "compileGate", "changedFiles"));
        artifacts.put("tests", pick(raw, "recommendedTests", "mavenCommands", "testExecutionResults", "testFailureType", "failedTestFiles", "failedAssertions"));
        artifacts.put("releaseRisk", pick(raw, "releaseRiskReport", "riskPoints", "observationMetrics",
                "rollbackConcerns", "humanApprovalPoints", "manualTakeoverRequired",
                "autoPatchBlockedReason", "verificationBlockedReason", "blockedAutomationSummary"));
        artifacts.put("guardrails", guardrails == null ? Map.of() : guardrails);
        return artifacts;
    }

    private Map<String, Object> buildStageArtifacts(String stageId, Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        return switch (stageId) {
            case "ops_evidence" -> pick(raw, "evidenceCoverage", "evidenceProvenance", "evidenceSources", "rootCause", "confidence", "traceId");
            case "code_localization" -> pick(raw, "targetFiles", "targetMethods", "suspiciousLocations",
                    "localizationConfidence", "codeSearchMatches", "finalAnswer", "turns", "trace",
                    "recommendedTests", "strategyType", "stopReason", "evidenceGraphSummary",
                    "evidenceGraphRankedCodeNodes", "evidenceGraph");
            case "knowledge_rag" -> pick(raw, "knowledgeMatches", "runbookMatches");
            case "code_repair" -> pick(raw, "llmGenerated", "patchGenerated", "rootCause", "patchApply", "patchScopeGuard", "patchSandbox", "patchQuality", "compileGate");
            case "test_verification" -> pick(raw, "recommendedTests", "mavenCommands", "testExecutionResults",
                    "testExecutionAsync", "queuedBackgroundTasks", "backgroundToolTasks", "taskNotifications",
                    "testFailureType", "failedTestFiles");
            case "release_risk" -> pick(raw, "releaseRiskReport", "humanApprovalPoints", "releaseRiskReasoning",
                    "modelRouting", "manualTakeoverRequired", "autoPatchBlockedReason",
                    "verificationBlockedReason", "blockedAutomationSummary");
            default -> Map.of();
        };
    }

    private Map<String, Object> buildHighlights(String skillId, Map<String, Object> rawEvidence) {
        Map<String, Object> highlights = new LinkedHashMap<>();
        putIfPresent(highlights, "changedFiles", rawEvidence.get("changedFiles"));
        putIfPresent(highlights, "relatedTestFiles", rawEvidence.get("relatedTestFiles"));
        putIfPresent(highlights, "codeHints", rawEvidence.get("codeHints"));
        putIfPresent(highlights, "evidenceGraphSummary", rawEvidence.get("evidenceGraphSummary"));
        putIfPresent(highlights, "evidenceGraphRankedCodeNodes", rawEvidence.get("evidenceGraphRankedCodeNodes"));
        putIfPresent(highlights, "codeSearchMatches", rawEvidence.get("codeSearchMatches"));
        putIfPresent(highlights, "findings", rawEvidence.get("findings"));
        putIfPresent(highlights, "baselineFindings", rawEvidence.get("baselineFindings"));
        putIfPresent(highlights, "knowledgeMatches", rawEvidence.get("knowledgeMatches"));
        putIfPresent(highlights, "llmReviewSuccess", rawEvidence.get("llmReviewSuccess"));
        putIfPresent(highlights, "llmReviewFallback", rawEvidence.get("llmReviewFallback"));
        putIfPresent(highlights, "llmReviewError", rawEvidence.get("llmReviewError"));
        putIfPresent(highlights, "opsDiagnosis", rawEvidence.get("opsDiagnosis"));
        putIfPresent(highlights, "patchDraft", rawEvidence.get("patchDraft"));
        putIfPresent(highlights, "patchValidation", rawEvidence.get("patchValidation"));
        putIfPresent(highlights, "patchApply", rawEvidence.get("patchApply"));
        putIfPresent(highlights, "rootCause", rawEvidence.get("rootCause"));
        putIfPresent(highlights, "confidence", rawEvidence.get("confidence"));
        putIfPresent(highlights, "llmGenerated", rawEvidence.get("llmGenerated"));
        putIfPresent(highlights, "llmErrorMessage", rawEvidence.get("llmErrorMessage"));
        putIfPresent(highlights, "codeSnippets", rawEvidence.get("codeSnippets"));
        putIfPresent(highlights, "mavenCommands", rawEvidence.get("mavenCommands"));
        putIfPresent(highlights, "recommendedTests", rawEvidence.get("recommendedTests"));
        putIfPresent(highlights, "coverageGaps", rawEvidence.get("coverageGaps"));
        putIfPresent(highlights, "testExecutionResults", rawEvidence.get("testExecutionResults"));
        putIfPresent(highlights, "testExecutionAsync", rawEvidence.get("testExecutionAsync"));
        putIfPresent(highlights, "queuedBackgroundTasks", rawEvidence.get("queuedBackgroundTasks"));
        putIfPresent(highlights, "backgroundToolTasks", rawEvidence.get("backgroundToolTasks"));
        putIfPresent(highlights, "taskNotifications", rawEvidence.get("taskNotifications"));
        putIfPresent(highlights, "testPatchGenerated", rawEvidence.get("testPatchGenerated"));
        putIfPresent(highlights, "testPatchTargetFiles", rawEvidence.get("testPatchTargetFiles"));
        putIfPresent(highlights, "testPatchDraft", rawEvidence.get("testPatchDraft"));
        putIfPresent(highlights, "testPatchValidation", rawEvidence.get("testPatchValidation"));
        putIfPresent(highlights, "testPatchApply", rawEvidence.get("testPatchApply"));
        putIfPresent(highlights, "riskPoints", rawEvidence.get("riskPoints"));
        putIfPresent(highlights, "observationMetrics", rawEvidence.get("observationMetrics"));
        putIfPresent(highlights, "rollbackConcerns", rawEvidence.get("rollbackConcerns"));
        putIfPresent(highlights, "manualTakeoverRequired", rawEvidence.get("manualTakeoverRequired"));
        putIfPresent(highlights, "autoPatchBlockedReason", rawEvidence.get("autoPatchBlockedReason"));
        putIfPresent(highlights, "verificationBlockedReason", rawEvidence.get("verificationBlockedReason"));
        putIfPresent(highlights, "blockedAutomationSummary", rawEvidence.get("blockedAutomationSummary"));
        putIfPresent(highlights, "agentRuntime", rawEvidence.get("agentRuntime"));
        putIfPresent(highlights, "toolRuntime", rawEvidence.get("toolRuntime"));
        if ("agent_loop_investigation".equals(skillId)) {
            putAgentLoopHighlights(highlights, rawEvidence);
        }
        highlights.put("skillId", skillId);
        return highlights;
    }

    private void putAgentLoopHighlights(Map<String, Object> highlights, Map<String, Object> rawEvidence) {
        putIfPresent(highlights, "agentLoopFinalAnswer", rawEvidence.get("finalAnswer"));
        putIfPresent(highlights, "agentLoopTurns", rawEvidence.get("turns"));
        putIfPresent(highlights, "agentLoopTrace", rawEvidence.get("trace"));
        putIfPresent(highlights, "agentLoopStopReason", rawEvidence.get("stopReason"));
        putIfPresent(highlights, "targetFiles", rawEvidence.get("targetFiles"));
        putIfPresent(highlights, "recommendedTests", rawEvidence.get("recommendedTests"));
        putIfPresent(highlights, "strategyType", rawEvidence.get("strategyType"));
        putIfPresent(highlights, "localizationConfidence", rawEvidence.get("localizationConfidence"));
    }

    private Map<String, Object> collectLatestRawOutputs(EngineeringTaskEntity task) {
        Map<String, Object> raw = new LinkedHashMap<>();
        if (task.getSteps() == null) {
            return raw;
        }
        for (EngineeringTaskStepEntity step : task.getSteps()) {
            raw.putAll(parseRawEvidence(step.getRawEvidenceJson()));
        }
        return raw;
    }

    private Map<String, EngineeringTaskStepEntity> latestStepBySkill(EngineeringTaskEntity task) {
        Map<String, EngineeringTaskStepEntity> steps = new LinkedHashMap<>();
        if (task.getSteps() == null) {
            return steps;
        }
        for (EngineeringTaskStepEntity step : task.getSteps()) {
            if (step.getSelectedSkill() != null) {
                steps.put(step.getSelectedSkill(), step);
            }
        }
        return steps;
    }

    private Map<String, Map<String, Object>> rawOutputBySkill(EngineeringTaskEntity task) {
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        if (task.getSteps() == null) {
            return outputs;
        }
        for (EngineeringTaskStepEntity step : task.getSteps()) {
            if (step.getSelectedSkill() != null) {
                outputs.put(step.getSelectedSkill(), parseRawEvidence(step.getRawEvidenceJson()));
            }
        }
        return outputs;
    }

    private EngineeringTaskStepEntity findStageStep(StageDefinition stage, Map<String, EngineeringTaskStepEntity> steps) {
        for (String skillId : stage.skillIds()) {
            if (steps.containsKey(skillId)) {
                return steps.get(skillId);
            }
        }
        return null;
    }

    private Map<String, Object> findStageRaw(StageDefinition stage, Map<String, Map<String, Object>> rawBySkill) {
        for (String skillId : stage.skillIds()) {
            if (rawBySkill.containsKey(skillId)) {
                return rawBySkill.get(skillId);
            }
        }
        return Map.of();
    }

    private Map<String, Object> pick(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Set<String> keySet = Set.of(keys);
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (keySet.contains(key) && value != null) {
                result.put(key, value);
            }
        });
        return result;
    }

    private Map<String, Object> parseRawEvidence(String rawEvidenceJson) {
        if (rawEvidenceJson == null || rawEvidenceJson.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject object = JSON.parseObject(rawEvidenceJson);
            return new LinkedHashMap<>(object);
        } catch (Exception ignored) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("rawText", rawEvidenceJson);
            return fallback;
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record StageDefinition(String stageId, String stageName, List<String> skillIds) {
    }

}
