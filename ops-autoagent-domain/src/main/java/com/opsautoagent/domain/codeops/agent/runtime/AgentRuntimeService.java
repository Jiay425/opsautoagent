package com.opsautoagent.domain.codeops.agent.runtime;

import com.opsautoagent.domain.codeops.agent.orchestrator.IncidentFixOrchestratorDecision;
import com.opsautoagent.domain.codeops.agent.skill.EngineeringSkill;
import com.opsautoagent.domain.codeops.agent.tool.ToolRuntimeService;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.IncidentFixWorkingMemory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentRuntimeService {

    private final ToolRuntimeService toolRuntimeService;

    public AgentRuntimeService(ToolRuntimeService toolRuntimeService) {
        this.toolRuntimeService = toolRuntimeService;
    }

    public AgentExecutionContext begin(EngineeringTaskEntity task,
                                       int stepNo,
                                       IncidentFixOrchestratorDecision decision,
                                       EngineeringSkill skill,
                                       IncidentFixWorkingMemory memory,
                                       int maxReflectionRounds,
                                       int currentReflectionRound) {
        EngineeringSkillEntity metadata = skill == null ? null : skill.metadata();
        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionId(UUID.randomUUID().toString())
                .taskId(task.getTaskId())
                .taskType(task.getTaskType())
                .traceId(resolveTraceId(task))
                .stepNo(stepNo)
                .selectedSkill(decision.getSelectedSkill())
                .decision(decision.getDecision())
                .decisionReason(decision.getReason())
                .goal(task.getGoal())
                .repository(task.getRepository())
                .changeRef(task.getChangeRef())
                .budget(buildBudget(task, stepNo, maxReflectionRounds, currentReflectionRound))
                .focusAreas(task.getFocusAreas())
                .allowedTools(metadata == null ? List.of() : metadata.getRequiredTools())
                .toolConstraints(buildToolConstraints(metadata))
                .workingMemorySummary(buildWorkingMemorySummary(memory))
                .startTime(LocalDateTime.now())
                .build();
        putRuntimeContext(task, context);
        appendRuntimeTrace(task, toMap(AgentStepTrace.builder()
                .executionId(context.getExecutionId())
                .taskId(context.getTaskId())
                .taskType(context.getTaskType())
                .traceId(context.getTraceId())
                .stepNo(stepNo)
                .agentOrSkill(context.getSelectedSkill())
                .decision(context.getDecision())
                .reason(context.getDecisionReason())
                .status(AgentExecutionStatus.RUNNING.name())
                .budget(context.getBudget())
                .toolConstraints(context.getToolConstraints())
                .startTime(context.getStartTime())
                .build()));
        toolRuntimeService.bind(task, context);
        return context;
    }

    public AgentStepTrace finish(EngineeringTaskEntity task,
                                 AgentExecutionContext context,
                                 EngineeringSkillResultEntity result) {
        LocalDateTime endTime = LocalDateTime.now();
        AgentStepTrace trace = AgentStepTrace.builder()
                .executionId(context.getExecutionId())
                .taskId(context.getTaskId())
                .taskType(context.getTaskType())
                .traceId(context.getTraceId())
                .stepNo(context.getStepNo())
                .agentOrSkill(context.getSelectedSkill())
                .decision(context.getDecision())
                .reason(context.getDecisionReason())
                .status(result == null ? AgentExecutionStatus.FAILED.name() : normalizeStatus(result.getStatus()))
                .summary(result == null ? "" : result.getSummary())
                .costMillis(Duration.between(context.getStartTime(), endTime).toMillis())
                .budget(buildBudget(task, context.getStepNo(),
                        context.getBudget() == null ? 0 : value(context.getBudget().getMaxReflectionRounds()),
                        context.getBudget() == null ? 0 : value(context.getBudget().getCurrentReflectionRound())))
                .evidence(result == null ? List.of() : result.getEvidence())
                .nextActions(result == null ? List.of() : result.getNextActions())
                .toolConstraints(context.getToolConstraints())
                .outputHighlights(extractHighlights(result == null ? null : result.getRawOutput()))
                .startTime(context.getStartTime())
                .endTime(endTime)
                .build();
        appendRuntimeTrace(task, toMap(trace));
        toolRuntimeService.clear();
        clearRuntimeContext(task);
        return trace;
    }

    public AgentStepTrace fail(EngineeringTaskEntity task,
                               AgentExecutionContext context,
                               Exception error) {
        LocalDateTime endTime = LocalDateTime.now();
        AgentStepTrace trace = AgentStepTrace.builder()
                .executionId(context.getExecutionId())
                .taskId(context.getTaskId())
                .taskType(context.getTaskType())
                .traceId(context.getTraceId())
                .stepNo(context.getStepNo())
                .agentOrSkill(context.getSelectedSkill())
                .decision(context.getDecision())
                .reason(context.getDecisionReason())
                .status(AgentExecutionStatus.FAILED.name())
                .summary("Agent step failed: " + (error == null ? "" : error.getMessage()))
                .costMillis(Duration.between(context.getStartTime(), endTime).toMillis())
                .budget(context.getBudget())
                .toolConstraints(context.getToolConstraints())
                .errorType(error == null ? "" : error.getClass().getSimpleName())
                .errorMessage(error == null ? "" : error.getMessage())
                .startTime(context.getStartTime())
                .endTime(endTime)
                .build();
        appendRuntimeTrace(task, toMap(trace));
        toolRuntimeService.clear();
        clearRuntimeContext(task);
        return trace;
    }

    private AgentBudget buildBudget(EngineeringTaskEntity task,
                                    int stepNo,
                                    int maxReflectionRounds,
                                    int currentReflectionRound) {
        int maxToolCalls = task.getMaxToolCalls() == null ? 0 : task.getMaxToolCalls();
        int usedToolCalls = task.getUsedToolCalls() == null ? 0 : task.getUsedToolCalls();
        return AgentBudget.builder()
                .maxRounds(task.getMaxRounds())
                .currentRound(stepNo)
                .maxToolCalls(maxToolCalls)
                .usedToolCalls(usedToolCalls)
                .remainingToolCalls(Math.max(0, maxToolCalls - usedToolCalls))
                .maxReflectionRounds(maxReflectionRounds)
                .currentReflectionRound(currentReflectionRound)
                .build();
    }

    private Map<String, Object> buildToolConstraints(EngineeringSkillEntity metadata) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        if (metadata == null) {
            return constraints;
        }
        constraints.put("skillId", metadata.getSkillId());
        constraints.put("requiredTools", metadata.getRequiredTools() == null ? List.of() : metadata.getRequiredTools());
        constraints.put("riskLevel", metadata.getRiskLevel());
        constraints.put("supportedTaskTypes", metadata.getSupportedTaskTypes() == null ? List.of() : metadata.getSupportedTaskTypes());
        return constraints;
    }

    private Map<String, Object> buildWorkingMemorySummary(IncidentFixWorkingMemory memory) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (memory == null) {
            return summary;
        }
        summary.put("hasOpsEvidence", memory.getOpsEvidence() != null && !memory.getOpsEvidence().isEmpty());
        summary.put("hasCodeLocalization", memory.getCodeLocalization() != null && !memory.getCodeLocalization().isEmpty());
        summary.put("hasPatch", memory.getPatchGeneration() != null && !memory.getPatchGeneration().isEmpty());
        summary.put("hasTestVerification", memory.getTestVerification() != null && !memory.getTestVerification().isEmpty());
        summary.put("hasReleaseRisk", memory.getReleaseRisk() != null && !memory.getReleaseRisk().isEmpty());
        summary.put("traceSize", memory.getAgentTrace() == null ? 0 : memory.getAgentTrace().size());
        return summary;
    }

    private Map<String, Object> extractHighlights(Map<String, Object> rawOutput) {
        Map<String, Object> highlights = new LinkedHashMap<>();
        if (rawOutput == null || rawOutput.isEmpty()) {
            return highlights;
        }
        copyIfPresent(highlights, rawOutput, "phase");
        copyIfPresent(highlights, rawOutput, "rootCause");
        copyIfPresent(highlights, rawOutput, "confidence");
        copyIfPresent(highlights, rawOutput, "strategyType");
        copyIfPresent(highlights, rawOutput, "shouldEnterCodeRepair");
        copyIfPresent(highlights, rawOutput, "targetFiles");
        copyIfPresent(highlights, rawOutput, "targetMethods");
        copyIfPresent(highlights, rawOutput, "scopeType");
        copyIfPresent(highlights, rawOutput, "patchGenerated");
        copyIfPresent(highlights, rawOutput, "llmGenerated");
        copyIfPresent(highlights, rawOutput, "patchScopeGuard");
        copyIfPresent(highlights, rawOutput, "testExecutionResults");
        copyIfPresent(highlights, rawOutput, "releaseRiskReport");
        copyIfPresent(highlights, rawOutput, "modelRouting");
        return highlights;
    }

    private void putRuntimeContext(EngineeringTaskEntity task, AgentExecutionContext runtimeContext) {
        Map<String, Object> context = ensureContext(task);
        context.put("agentRuntimeContext", runtimeContext);
    }

    private void clearRuntimeContext(EngineeringTaskEntity task) {
        if (task.getContext() != null) {
            task.getContext().remove("agentRuntimeContext");
        }
    }

    @SuppressWarnings("unchecked")
    private void appendRuntimeTrace(EngineeringTaskEntity task, Map<String, Object> trace) {
        Map<String, Object> context = ensureContext(task);
        Object existing = context.get("agentRuntimeTrace");
        List<Map<String, Object>> traces;
        if (existing instanceof List<?> list) {
            traces = (List<Map<String, Object>>) list;
        } else {
            traces = new java.util.ArrayList<>();
            context.put("agentRuntimeTrace", traces);
        }
        traces.add(trace);
    }

    private Map<String, Object> ensureContext(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            task.setContext(new LinkedHashMap<>());
        }
        return task.getContext();
    }

    private String resolveTraceId(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return task.getTaskId();
        }
        Object traceId = task.getContext().get("traceId");
        return traceId == null || String.valueOf(traceId).isBlank() ? task.getTaskId() : String.valueOf(traceId);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return AgentExecutionStatus.SUCCESS.name();
        }
        return status;
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private Map<String, Object> toMap(AgentStepTrace trace) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", trace.getExecutionId());
        map.put("taskId", trace.getTaskId());
        map.put("taskType", trace.getTaskType());
        map.put("traceId", trace.getTraceId());
        map.put("stepNo", trace.getStepNo());
        map.put("agentOrSkill", trace.getAgentOrSkill());
        map.put("decision", trace.getDecision());
        map.put("reason", trace.getReason());
        map.put("status", trace.getStatus());
        map.put("summary", trace.getSummary());
        map.put("costMillis", trace.getCostMillis());
        map.put("budget", trace.getBudget());
        map.put("evidence", trace.getEvidence());
        map.put("nextActions", trace.getNextActions());
        map.put("toolConstraints", trace.getToolConstraints());
        map.put("outputHighlights", trace.getOutputHighlights());
        map.put("errorType", trace.getErrorType());
        map.put("errorMessage", trace.getErrorMessage());
        map.put("startTime", trace.getStartTime() == null ? null : trace.getStartTime().toString());
        map.put("endTime", trace.getEndTime() == null ? null : trace.getEndTime().toString());
        return map;
    }

}
