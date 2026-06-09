package com.opsautoagent.domain.codeops.agent.loop;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentLoopResult {

    private String status;

    private String finalAnswer;

    private String stopReason;

    private int turns;

    @Builder.Default
    private List<AgentLoopTraceItem> trace = List.of();

    @Builder.Default
    private List<AgentLoopStep> steps = List.of();

    public boolean isSuccess() {
        return "COMPLETED".equalsIgnoreCase(status);
    }

}
