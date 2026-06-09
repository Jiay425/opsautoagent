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
public class AgentLoopDecision {

    private String thoughtSummary;

    @Builder.Default
    private List<AgentLoopToolCall> toolCalls = List.of();

    private String finalAnswer;

    public boolean isFinal() {
        return finalAnswer != null && !finalAnswer.isBlank();
    }

}
