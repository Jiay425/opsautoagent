package com.opsautoagent.domain.codeops.agent.orchestrator;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IncidentFixOrchestratorDecision {

    private String decision;

    private String selectedSkill;

    private String reason;

    public static IncidentFixOrchestratorDecision call(String selectedSkill, String reason) {
        return IncidentFixOrchestratorDecision.builder()
                .decision("CALL_SKILL")
                .selectedSkill(selectedSkill)
                .reason(reason)
                .build();
    }

    public static IncidentFixOrchestratorDecision stop(String reason) {
        return IncidentFixOrchestratorDecision.builder()
                .decision("STOP")
                .reason(reason)
                .build();
    }

    public boolean shouldStop() {
        return "STOP".equals(decision);
    }

}
