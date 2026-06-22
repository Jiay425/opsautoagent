package com.opsautoagent.domain.codeops.agent.repair;

import com.opsautoagent.domain.codeops.agent.loop.AgentLoopDecision;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopRequest;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopResult;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopStep;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopToolCall;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolRequest;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolResult;
import com.opsautoagent.domain.codeops.agent.tool.ToolPermissionDecision;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.PatchAttemptEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RepairAgentLoopService {

    private static final int DEFAULT_MAX_TURNS = 8;
    private static final int HARD_MAX_TURNS = 16;
    private static final Set<String> REPAIR_TOOLS = Set.of(
            "repo.read_file_snippet",
            "repo.exact_replace",
            "repo.maven",
            "repo.maven_background",
            "task.background_status"
    );

    private final com.opsautoagent.domain.codeops.agent.tool.EngineeringToolRegistry toolRegistry;
    private final RepairObservationService repairObservationService;

    public RepairAgentLoopService(com.opsautoagent.domain.codeops.agent.tool.EngineeringToolRegistry toolRegistry,
                                  RepairObservationService repairObservationService) {
        this.toolRegistry = toolRegistry;
        this.repairObservationService = repairObservationService;
    }

    public AgentLoopResult run(RepairAgentLoopRequest request, AgentLoopModelClient modelClient) {
        if (modelClient == null) {
            return result("FAILED", "", "modelClient is required", 0, List.of());
        }
        EngineeringTaskEntity task = ensureTask(request);
        AgentLoopRequest loopRequest = AgentLoopRequest.builder()
                .goal(request == null ? "" : request.getGoal())
                .task(task)
                .metadata(metadata(request))
                .maxTurns(normalizeMaxTurns(request == null ? 0 : request.getMaxTurns()))
                .build();
        List<AgentLoopStep> steps = new ArrayList<>();
        int maxTurns = normalizeMaxTurns(request == null ? 0 : request.getMaxTurns());
        for (int turn = 1; turn <= maxTurns; turn++) {
            AgentLoopDecision decision = modelClient.next(loopRequest, List.copyOf(steps));
            if (decision == null) {
                recordAttempt(task, request, steps, false, "model returned null decision");
                return result("FAILED", "", "model returned null decision", turn, steps);
            }
            if (decision.isFinal()) {
                boolean recovered = isRecovered(steps);
                recordAttempt(task, request, steps, recovered, decision.getFinalAnswer());
                return result(recovered ? "COMPLETED" : "FAILED", decision.getFinalAnswer(), "final_answer", turn, steps);
            }
            if (decision.getToolCalls() == null || decision.getToolCalls().isEmpty()) {
                boolean recovered = isRecovered(steps);
                recordAttempt(task, request, steps, recovered, "no_tool_calls");
                return result(recovered ? "COMPLETED" : "FAILED", "", "no_tool_calls", turn, steps);
            }
            for (AgentLoopToolCall toolCall : decision.getToolCalls()) {
                AgentLoopStep step = executeRepairTool(task, request, turn, toolCall);
                steps.add(step);
                if (isHardStop(step.getToolResult())) {
                    recordAttempt(task, request, steps, false, step.getToolResult().getSummary());
                    return result(step.getToolResult().getStatus(), "", step.getToolResult().getSummary(), turn, steps);
                }
            }
        }
        boolean recovered = isRecovered(steps);
        recordAttempt(task, request, steps, recovered, "repair loop reached maxTurns=" + maxTurns);
        return result(recovered ? "COMPLETED" : "MAX_TURNS_REACHED", "", "repair loop reached maxTurns=" + maxTurns,
                maxTurns, steps);
    }

    private AgentLoopStep executeRepairTool(EngineeringTaskEntity task,
                                            RepairAgentLoopRequest request,
                                            int turn,
                                            AgentLoopToolCall toolCall) {
        LocalDateTime start = LocalDateTime.now();
        Map<String, Object> arguments = new LinkedHashMap<>(toolCall == null || toolCall.getArguments() == null
                ? Map.of() : toolCall.getArguments());
        if (!arguments.containsKey("repository")) {
            arguments.put("repository", repository(request, task));
        }
        EngineeringToolRequest toolRequest = EngineeringToolRequest.builder()
                .toolName(toolCall == null ? "" : toolCall.getToolName())
                .arguments(arguments)
                .task(task)
                .build();
        ToolPermissionDecision permissionDecision = toolRegistry.previewPermission(toolRequest);
        EngineeringToolResult toolResult = isRepairTool(toolRequest.getToolName())
                ? toolRegistry.execute(toolRequest)
                : deniedTool(toolRequest);
        return AgentLoopStep.builder()
                .turnNo(turn)
                .toolCallId(toolCall == null ? "" : toolCall.getToolCallId())
                .toolName(toolRequest.getToolName())
                .arguments(arguments)
                .permissionDecision(permissionDecision)
                .toolResult(toolResult)
                .startedAt(start)
                .finishedAt(LocalDateTime.now())
                .build();
    }

    private EngineeringToolResult deniedTool(EngineeringToolRequest request) {
        EngineeringToolResult result = EngineeringToolResult.denied(request == null ? "" : request.getToolName(),
                "Repair loop allows only read_file_snippet, exact_replace, maven and background status tools");
        if (request != null && request.getTask() != null) {
            repairObservationService.recordToolObservation(request.getTask(), "REPAIR_AGENT_LOOP",
                    request, null, result);
        }
        return result;
    }

    private void recordAttempt(EngineeringTaskEntity task,
                               RepairAgentLoopRequest request,
                               List<AgentLoopStep> steps,
                               boolean recovered,
                               String outcome) {
        if (task == null) {
            return;
        }
        repairObservationService.recordPatchAttempt(task, PatchAttemptEntity.builder()
                .skillId("repair_agent_loop")
                .editMethod(editMethod(steps))
                .inputFailureDiagnostic(request == null || request.getInputFailureDiagnostic() == null
                        ? Map.of() : request.getInputFailureDiagnostic())
                .filesRead(filesRead(steps))
                .scopeDecision(Map.of("source", "repair_agent_loop", "allowedTools", REPAIR_TOOLS))
                .applyResult(latestToolOutput(steps, "repo.exact_replace"))
                .compileResult(latestToolOutput(steps, "repo.maven"))
                .testResult(Map.of("requested", false, "reason", "repair agent loop compile gate only"))
                .nextFailureDiagnostic(Map.of("outcome", outcome == null ? "" : outcome))
                .recovered(recovered)
                .build());
    }

    private EngineeringTaskEntity ensureTask(RepairAgentLoopRequest request) {
        if (request != null && request.getTask() != null) {
            if (request.getTask().getContext() == null) {
                request.getTask().setContext(new LinkedHashMap<>());
            }
            if ((request.getTask().getRepository() == null || request.getTask().getRepository().isBlank())
                    && request.getRepository() != null) {
                request.getTask().setRepository(request.getRepository());
            }
            return request.getTask();
        }
        return EngineeringTaskEntity.builder()
                .taskId("repair-loop-task")
                .taskType("BUG_FIX")
                .goal(request == null ? "" : request.getGoal())
                .repository(request == null ? "" : request.getRepository())
                .context(new LinkedHashMap<>())
                .build();
    }

    private Map<String, Object> metadata(RepairAgentLoopRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("loopType", "repair_agent_loop");
        metadata.put("allowedTools", REPAIR_TOOLS);
        metadata.put("inputFailureDiagnostic", request == null || request.getInputFailureDiagnostic() == null
                ? Map.of() : request.getInputFailureDiagnostic());
        return metadata;
    }

    private String repository(RepairAgentLoopRequest request, EngineeringTaskEntity task) {
        if (request != null && request.getRepository() != null && !request.getRepository().isBlank()) {
            return request.getRepository();
        }
        return task == null || task.getRepository() == null ? "" : task.getRepository();
    }

    private boolean isRepairTool(String toolName) {
        return toolName != null && REPAIR_TOOLS.contains(toolName);
    }

    private boolean isHardStop(EngineeringToolResult result) {
        if (result == null || result.getStatus() == null) {
            return false;
        }
        return "REQUIRES_APPROVAL".equalsIgnoreCase(result.getStatus())
                || "DENIED".equalsIgnoreCase(result.getStatus());
    }

    private boolean isRecovered(List<AgentLoopStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        boolean hasEdit = false;
        boolean editSucceeded = false;
        boolean compileSucceeded = false;
        for (AgentLoopStep step : steps) {
            EngineeringToolResult result = step.getToolResult();
            if ("repo.exact_replace".equals(step.getToolName())) {
                hasEdit = true;
                editSucceeded = result != null && result.isSuccess();
            }
            if ("repo.maven".equals(step.getToolName())) {
                compileSucceeded = result != null && result.isSuccess();
            }
        }
        return hasEdit && editSucceeded && (compileSucceeded || steps.stream().noneMatch(step -> "repo.maven".equals(step.getToolName())));
    }

    private String editMethod(List<AgentLoopStep> steps) {
        if (steps != null && steps.stream().anyMatch(step -> "repo.exact_replace".equals(step.getToolName()))) {
            return "exactReplace";
        }
        return "none";
    }

    private List<String> filesRead(List<AgentLoopStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        Set<String> files = new LinkedHashSet<>();
        for (AgentLoopStep step : steps) {
            if ("repo.read_file_snippet".equals(step.getToolName()) && step.getArguments() != null) {
                Object filePath = step.getArguments().get("filePath");
                if (filePath != null && !String.valueOf(filePath).isBlank()) {
                    files.add(String.valueOf(filePath));
                }
            }
        }
        return new ArrayList<>(files);
    }

    private Map<String, Object> latestToolOutput(List<AgentLoopStep> steps, String toolName) {
        if (steps == null || steps.isEmpty()) {
            return Map.of();
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            AgentLoopStep step = steps.get(i);
            if (!toolName.equals(step.getToolName())) {
                continue;
            }
            Map<String, Object> output = new LinkedHashMap<>();
            EngineeringToolResult result = step.getToolResult();
            output.put("toolName", toolName);
            output.put("status", result == null ? "" : result.getStatus());
            output.put("success", result != null && result.isSuccess());
            output.put("summary", result == null ? "" : result.getSummary());
            output.put("errorType", result == null ? "" : result.getErrorType());
            output.put("output", result == null ? "" : result.getOutput());
            return output;
        }
        return Map.of();
    }

    private AgentLoopResult result(String status, String finalAnswer, String stopReason, int turns, List<AgentLoopStep> steps) {
        return AgentLoopResult.builder()
                .status(status)
                .finalAnswer(finalAnswer)
                .stopReason(stopReason)
                .turns(turns)
                .steps(steps == null ? List.of() : List.copyOf(steps))
                .build();
    }

    private int normalizeMaxTurns(int maxTurns) {
        if (maxTurns <= 0) {
            return DEFAULT_MAX_TURNS;
        }
        return Math.min(maxTurns, HARD_MAX_TURNS);
    }
}
