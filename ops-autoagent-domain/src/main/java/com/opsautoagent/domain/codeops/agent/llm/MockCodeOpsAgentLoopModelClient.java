package com.opsautoagent.domain.codeops.agent.llm;

import com.opsautoagent.domain.codeops.agent.loop.AgentLoopDecision;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopRequest;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopStep;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopToolCall;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MockCodeOpsAgentLoopModelClient implements AgentLoopModelClient {

    @Override
    public AgentLoopDecision next(AgentLoopRequest request, List<AgentLoopStep> previousSteps) {
        String forcedToolName = stringMetadata(request, "forcedToolName");
        if (!forcedToolName.isBlank()) {
            return forcedToolDecision(request, previousSteps, forcedToolName);
        }
        if (previousSteps == null || previousSteps.isEmpty()) {
            return AgentLoopDecision.builder()
                    .thoughtSummary("Dry-run first turn: search repository text for goal keywords.")
                    .toolCalls(List.of(AgentLoopToolCall.builder()
                            .toolName("repo.search_text")
                            .arguments(searchArguments(request))
                            .build()))
                    .finalAnswer("")
                    .build();
        }
        AgentLoopStep lastStep = previousSteps.get(previousSteps.size() - 1);
        return AgentLoopDecision.builder()
                .thoughtSummary("Dry-run second turn: summarize the observed tool result.")
                .toolCalls(List.of())
                .finalAnswer(finalAnswer(lastStep))
                .build();
    }

    private String finalAnswer(AgentLoopStep lastStep) {
        JSONObject json = new JSONObject(true);
        json.put("summary", "Dry-run agent loop completed. Last tool="
                + lastStep.getToolName()
                + ", status=" + (lastStep.getToolResult() == null ? "" : lastStep.getToolResult().getStatus())
                + ", summary=" + (lastStep.getToolResult() == null ? "" : lastStep.getToolResult().getSummary()));
        json.put("targetFiles", List.of("src/main/java/com/example/order/OrderServiceApplication.java"));
        json.put("recommendedTests", List.of("src/test/java/com/example/order/OrderServiceApplicationTests.java"));
        json.put("shouldEnterCodeRepair", true);
        json.put("localizationConfidence", "MEDIUM");
        json.put("missingEvidence", List.of("dry-run uses a deterministic mock summary instead of model reasoning"));
        return json.toJSONString();
    }

    @SuppressWarnings("unchecked")
    private AgentLoopDecision forcedToolDecision(AgentLoopRequest request,
                                                List<AgentLoopStep> previousSteps,
                                                String forcedToolName) {
        if (previousSteps == null || previousSteps.isEmpty()) {
            return AgentLoopDecision.builder()
                    .thoughtSummary("Dry-run forced tool turn: execute " + forcedToolName)
                    .toolCalls(List.of(AgentLoopToolCall.builder()
                            .toolName(forcedToolName)
                            .arguments(mapMetadata(request, "forcedToolArguments"))
                            .build()))
                    .finalAnswer("")
                    .build();
        }
        AgentLoopStep lastStep = previousSteps.get(previousSteps.size() - 1);
        if ("repo.maven_background".equals(lastStep.getToolName()) && previousSteps.size() == 1) {
            String backgroundTaskId = "";
            if (lastStep.getToolResult() != null && lastStep.getToolResult().getMetadata() != null) {
                Object value = lastStep.getToolResult().getMetadata().get("backgroundTaskId");
                backgroundTaskId = value == null ? "" : String.valueOf(value);
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("backgroundTaskId", backgroundTaskId);
            return AgentLoopDecision.builder()
                    .thoughtSummary("Dry-run forced tool status turn: query background task " + backgroundTaskId)
                    .toolCalls(List.of(AgentLoopToolCall.builder()
                            .toolName("task.background_status")
                            .arguments(arguments)
                            .build()))
                    .finalAnswer("")
                    .build();
        }
        return AgentLoopDecision.builder()
                .thoughtSummary("Dry-run forced tool final turn: summarize forced tool result.")
                .toolCalls(List.of())
                .finalAnswer(finalAnswer(lastStep))
                .build();
    }

    private Map<String, Object> searchArguments(AgentLoopRequest request) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        String repository = request == null || request.getTask() == null ? "" : request.getTask().getRepository();
        arguments.put("repository", repository);
        arguments.put("queries", List.of(resolveKeyword(request)));
        arguments.put("maxMatches", 20);
        return arguments;
    }

    private String stringMetadata(AgentLoopRequest request, String key) {
        if (request == null || request.getMetadata() == null) {
            return "";
        }
        Object value = request.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> mapMetadata(AgentLoopRequest request, String key) {
        if (request == null || request.getMetadata() == null) {
            return new LinkedHashMap<>();
        }
        Object value = request.getMetadata().get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((rawKey, rawValue) -> result.put(String.valueOf(rawKey), rawValue));
        return result;
    }

    private String resolveKeyword(AgentLoopRequest request) {
        String goal = request == null ? "" : request.getGoal();
        if (goal != null && goal.toLowerCase().contains("orderservice")) {
            return "OrderService";
        }
        if (goal != null && goal.toLowerCase().contains("order")) {
            return "Order";
        }
        return "Service";
    }

}
