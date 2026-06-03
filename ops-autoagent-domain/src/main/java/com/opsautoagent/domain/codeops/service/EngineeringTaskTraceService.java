package com.opsautoagent.domain.codeops.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.api.dto.CodeOpsTaskTraceDTO;
import com.opsautoagent.api.dto.CodeOpsTaskTraceStepDTO;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskStepEntity;
import com.opsautoagent.domain.codeops.model.entity.IncidentFixWorkingMemory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EngineeringTaskTraceService {

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

    private Map<String, Object> buildWorkingMemorySummary(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return Map.of();
        }
        Object memory = task.getContext().get("incidentFixWorkingMemory");
        if (!(memory instanceof IncidentFixWorkingMemory workingMemory)) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        putIfPresent(summary, "incidentSummary", workingMemory.getIncidentSummary());
        putIfPresent(summary, "codeHints", workingMemory.getCodeHints());
        putIfPresent(summary, "codeLocalization", compactMap(workingMemory.getCodeLocalization(),
                "localizationConfidence", "targetFiles", "targetMethods", "suspiciousLocations", "missingEvidence"));
        putIfPresent(summary, "rootCauseAnalysis", workingMemory.getRootCauseAnalysis());
        putIfPresent(summary, "patchGeneration", compactMap(workingMemory.getPatchGeneration(),
                "rootCause", "confidence", "targetFiles", "llmGenerated", "patchValidation"));
        putIfPresent(summary, "testVerification", compactMap(workingMemory.getTestVerification(),
                "recommendedTests", "coverageGaps", "mavenCommands", "testExecutionResults"));
        putIfPresent(summary, "releaseRisk", compactMap(workingMemory.getReleaseRisk(),
                "releaseRiskReport", "humanApprovalPoints", "releaseRiskReasoning"));
        putIfPresent(summary, "agentTrace", workingMemory.getAgentTrace());
        putIfPresent(summary, "agentRuntimeTrace", task.getContext().get("agentRuntimeTrace"));
        putIfPresent(summary, "toolRuntimeTrace", task.getContext().get("toolRuntimeTrace"));
        return summary;
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

    private Map<String, Object> buildHighlights(String skillId, Map<String, Object> rawEvidence) {
        Map<String, Object> highlights = new LinkedHashMap<>();
        putIfPresent(highlights, "changedFiles", rawEvidence.get("changedFiles"));
        putIfPresent(highlights, "relatedTestFiles", rawEvidence.get("relatedTestFiles"));
        putIfPresent(highlights, "codeHints", rawEvidence.get("codeHints"));
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
        putIfPresent(highlights, "testPatchGenerated", rawEvidence.get("testPatchGenerated"));
        putIfPresent(highlights, "testPatchTargetFiles", rawEvidence.get("testPatchTargetFiles"));
        putIfPresent(highlights, "testPatchDraft", rawEvidence.get("testPatchDraft"));
        putIfPresent(highlights, "testPatchValidation", rawEvidence.get("testPatchValidation"));
        putIfPresent(highlights, "testPatchApply", rawEvidence.get("testPatchApply"));
        putIfPresent(highlights, "riskPoints", rawEvidence.get("riskPoints"));
        putIfPresent(highlights, "observationMetrics", rawEvidence.get("observationMetrics"));
        putIfPresent(highlights, "rollbackConcerns", rawEvidence.get("rollbackConcerns"));
        putIfPresent(highlights, "agentRuntime", rawEvidence.get("agentRuntime"));
        putIfPresent(highlights, "toolRuntime", rawEvidence.get("toolRuntime"));
        highlights.put("skillId", skillId);
        return highlights;
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

}
