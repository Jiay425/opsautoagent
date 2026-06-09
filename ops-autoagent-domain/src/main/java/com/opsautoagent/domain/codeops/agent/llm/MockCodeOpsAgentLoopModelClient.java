package com.opsautoagent.domain.codeops.agent.llm;

import com.opsautoagent.domain.codeops.agent.loop.AgentLoopDecision;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopRequest;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopStep;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopToolCall;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MockCodeOpsAgentLoopModelClient implements AgentLoopModelClient {

    @Override
    public AgentLoopDecision next(AgentLoopRequest request, List<AgentLoopStep> previousSteps) {
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
                .finalAnswer("Dry-run agent loop completed. Last tool="
                        + lastStep.getToolName()
                        + ", status=" + (lastStep.getToolResult() == null ? "" : lastStep.getToolResult().getStatus())
                        + ", summary=" + (lastStep.getToolResult() == null ? "" : lastStep.getToolResult().getSummary()))
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
