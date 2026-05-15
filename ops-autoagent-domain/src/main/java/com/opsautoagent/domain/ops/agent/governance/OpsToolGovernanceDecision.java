package com.opsautoagent.domain.ops.agent.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsToolGovernanceDecision {

    private boolean allowed;

    private String decision;

    private String reason;

    private String toolName;

    private String agentRole;

    private Integer totalToolCalls;

    private Integer toolCallCount;

    public static OpsToolGovernanceDecision allowed(String toolName,
                                                    String agentRole,
                                                    String reason,
                                                    Integer totalToolCalls,
                                                    Integer toolCallCount) {
        return OpsToolGovernanceDecision.builder()
                .allowed(true)
                .decision("ALLOW")
                .reason(reason)
                .toolName(toolName)
                .agentRole(agentRole)
                .totalToolCalls(totalToolCalls)
                .toolCallCount(toolCallCount)
                .build();
    }

    public static OpsToolGovernanceDecision denied(String toolName,
                                                   String agentRole,
                                                   String reason,
                                                   Integer totalToolCalls,
                                                   Integer toolCallCount) {
        return OpsToolGovernanceDecision.builder()
                .allowed(false)
                .decision("DENY")
                .reason(reason)
                .toolName(toolName)
                .agentRole(agentRole)
                .totalToolCalls(totalToolCalls)
                .toolCallCount(toolCallCount)
                .build();
    }

}

