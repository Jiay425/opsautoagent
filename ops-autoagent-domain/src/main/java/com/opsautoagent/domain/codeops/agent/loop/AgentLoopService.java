package com.opsautoagent.domain.codeops.agent.loop;

import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolRegistry;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolRequest;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolResult;
import com.opsautoagent.domain.codeops.agent.tool.ToolPermissionDecision;
import com.opsautoagent.domain.codeops.agent.task.BackgroundToolTaskService;
import com.opsautoagent.domain.codeops.model.entity.TaskNotificationEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentLoopService {

    private static final int DEFAULT_MAX_TURNS = 8;
    private static final int HARD_MAX_TURNS = 32;

    private final EngineeringToolRegistry toolRegistry;
    private final BackgroundToolTaskService backgroundToolTaskService;

    public AgentLoopService(EngineeringToolRegistry toolRegistry,
                            BackgroundToolTaskService backgroundToolTaskService) {
        this.toolRegistry = toolRegistry;
        this.backgroundToolTaskService = backgroundToolTaskService;
    }

    public AgentLoopResult run(AgentLoopRequest request, AgentLoopModelClient modelClient) {
        if (modelClient == null) {
            return AgentLoopResult.builder()
                    .status("FAILED")
                    .stopReason("modelClient is required")
                    .steps(List.of())
                    .build();
        }
        int maxTurns = normalizeMaxTurns(request == null ? 0 : request.getMaxTurns());
        List<AgentLoopStep> steps = new ArrayList<>();
        for (int turn = 1; turn <= maxTurns; turn++) {
            List<TaskNotificationEntity> consumedNotifications = consumeBackgroundNotifications(request);
            AgentLoopDecision decision = modelClient.next(request, List.copyOf(steps));
            if (decision == null) {
                return result("FAILED", "", "model returned null decision", turn, steps);
            }
            if (decision.isFinal()) {
                appendLoopTrace(request, turn, decision, steps, consumedNotifications);
                return result("COMPLETED", decision.getFinalAnswer(), "final_answer", turn, steps);
            }
            if (decision.getToolCalls() == null || decision.getToolCalls().isEmpty()) {
                appendLoopTrace(request, turn, decision, steps, consumedNotifications);
                return result("COMPLETED", "", "no_tool_calls", turn, steps);
            }
            for (AgentLoopToolCall toolCall : decision.getToolCalls()) {
                AgentLoopStep step = executeToolCall(request, turn, toolCall);
                steps.add(step);
                if (isHardStop(step.getToolResult())) {
                    appendLoopTrace(request, turn, decision, steps, consumedNotifications);
                    return result(step.getToolResult().getStatus(), "", step.getToolResult().getSummary(), turn, steps);
                }
            }
            appendLoopTrace(request, turn, decision, steps, consumedNotifications);
        }
        return result("MAX_TURNS_REACHED", "", "agent loop reached maxTurns=" + maxTurns, maxTurns, steps);
    }

    private AgentLoopStep executeToolCall(AgentLoopRequest request, int turn, AgentLoopToolCall toolCall) {
        LocalDateTime start = LocalDateTime.now();
        EngineeringToolRequest toolRequest = EngineeringToolRequest.builder()
                .toolName(toolCall == null ? "" : toolCall.getToolName())
                .arguments(toolCall == null || toolCall.getArguments() == null ? new LinkedHashMap<>() : toolCall.getArguments())
                .task(request == null ? null : request.getTask())
                .executionContext(request == null ? null : request.getExecutionContext())
                .build();
        ToolPermissionDecision permissionDecision = toolRegistry.previewPermission(toolRequest);
        EngineeringToolResult toolResult = permissionDecision.isAllowed()
                ? toolRegistry.execute(toolRequest)
                : deniedResult(toolRequest.getToolName(), permissionDecision);
        return AgentLoopStep.builder()
                .turnNo(turn)
                .toolCallId(toolCall == null ? "" : toolCall.getToolCallId())
                .toolName(toolRequest.getToolName())
                .arguments(toolRequest.getArguments())
                .permissionDecision(permissionDecision)
                .toolResult(toolResult)
                .startedAt(start)
                .finishedAt(LocalDateTime.now())
                .build();
    }

    private EngineeringToolResult deniedResult(String toolName, ToolPermissionDecision decision) {
        if (decision.isRequiresApproval()) {
            return EngineeringToolResult.requiresApproval(toolName, decision.getReason(), decision.getPolicy());
        }
        return EngineeringToolResult.denied(toolName, decision.getReason());
    }

    private boolean isHardStop(EngineeringToolResult result) {
        if (result == null || result.getStatus() == null) {
            return false;
        }
        return "REQUIRES_APPROVAL".equalsIgnoreCase(result.getStatus())
                || "DENIED".equalsIgnoreCase(result.getStatus());
    }

    private AgentLoopResult result(String status, String finalAnswer, String stopReason, int turns, List<AgentLoopStep> steps) {
        return AgentLoopResult.builder()
                .status(status)
                .finalAnswer(finalAnswer)
                .stopReason(stopReason)
                .turns(turns)
                .trace(buildTrace(steps))
                .steps(List.copyOf(steps))
                .build();
    }

    private List<TaskNotificationEntity> consumeBackgroundNotifications(AgentLoopRequest request) {
        if (request == null || request.getTask() == null || backgroundToolTaskService == null) {
            return List.of();
        }
        List<TaskNotificationEntity> consumed = backgroundToolTaskService.consumeTerminalNotifications(
                request.getTask(), "agent-loop-service");
        if (consumed.isEmpty()) {
            return List.of();
        }
        if (request.getMetadata() == null) {
            request.setMetadata(new LinkedHashMap<>());
        }
        List<Map<String, Object>> observations = consumed.stream()
                .map(this::notificationObservation)
                .toList();
        request.getMetadata().put("backgroundTaskObservations", observations);
        request.getMetadata().put("latestBackgroundTaskObservation", observations.get(observations.size() - 1));
        return consumed;
    }

    private int normalizeMaxTurns(int maxTurns) {
        if (maxTurns <= 0) {
            return DEFAULT_MAX_TURNS;
        }
        return Math.min(maxTurns, HARD_MAX_TURNS);
    }

    @SuppressWarnings("unchecked")
    private void appendLoopTrace(AgentLoopRequest request,
                                 int turn,
                                 AgentLoopDecision decision,
                                 List<AgentLoopStep> steps,
                                 List<TaskNotificationEntity> consumedNotifications) {
        if (request == null || request.getTask() == null) {
            return;
        }
        if (request.getTask().getContext() == null) {
            request.getTask().setContext(new LinkedHashMap<>());
        }
        Object existing = request.getTask().getContext().get("agentLoopTrace");
        List<Map<String, Object>> traces;
        if (existing instanceof List<?> list) {
            traces = (List<Map<String, Object>>) list;
        } else {
            traces = new ArrayList<>();
            request.getTask().getContext().put("agentLoopTrace", traces);
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("turn", turn);
        trace.put("thoughtSummary", decision == null ? "" : decision.getThoughtSummary());
        trace.put("final", decision != null && decision.isFinal());
        trace.put("stepCount", steps.size());
        trace.put("recentSteps", steps.stream()
                .skip(Math.max(0, steps.size() - 5))
                .map(this::compactStep)
                .toList());
        trace.put("consumedBackgroundNotifications", consumedNotifications == null
                ? List.of()
                : consumedNotifications.stream().map(this::notificationObservation).toList());
        trace.put("time", LocalDateTime.now().toString());
        traces.add(trace);
    }

    private Map<String, Object> notificationObservation(TaskNotificationEntity notification) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (notification == null) {
            return item;
        }
        item.put("notificationId", notification.getNotificationId());
        item.put("nodeId", notification.getNodeId());
        item.put("backgroundTaskId", notification.getBackgroundTaskId());
        item.put("type", notification.getType());
        item.put("status", notification.getStatus());
        item.put("summary", notification.getSummary());
        item.put("payload", notification.getPayload() == null ? Map.of() : notification.getPayload());
        return item;
    }

    private Map<String, Object> compactStep(AgentLoopStep step) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("turnNo", step.getTurnNo());
        item.put("toolCallId", step.getToolCallId());
        item.put("toolName", step.getToolName());
        item.put("permission", step.getPermissionDecision() == null ? "" : step.getPermissionDecision().getStatus());
        item.put("status", step.getToolResult() == null ? "" : step.getToolResult().getStatus());
        item.put("summary", step.getToolResult() == null ? "" : step.getToolResult().getSummary());
        return item;
    }

    private List<AgentLoopTraceItem> buildTrace(List<AgentLoopStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .map(step -> AgentLoopTraceItem.builder()
                        .turnNo(step.getTurnNo())
                        .toolName(step.getToolName())
                        .permission(step.getPermissionDecision() == null ? "" : step.getPermissionDecision().getStatus())
                        .toolStatus(step.getToolResult() == null ? "" : step.getToolResult().getStatus())
                        .summary(step.getToolResult() == null ? "" : step.getToolResult().getSummary())
                        .outputPreview(abbreviate(step.getToolResult() == null ? "" : String.valueOf(step.getToolResult().getOutput()), 600))
                        .build())
                .toList();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

}
