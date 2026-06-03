package com.opsautoagent.domain.codeops.agent.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentBudget {

    private Integer maxRounds;

    private Integer currentRound;

    private Integer maxToolCalls;

    private Integer usedToolCalls;

    private Integer remainingToolCalls;

    private Integer maxReflectionRounds;

    private Integer currentReflectionRound;

    public boolean toolBudgetExhausted() {
        return remainingToolCalls != null && remainingToolCalls <= 0;
    }

}
