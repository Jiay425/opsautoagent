package com.opsautoagent.domain.codeops.agent.orchestrator;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IncidentFixOrchestratorDecision {

    private String decision;

    private String selectedSkill;

    private String reason;

    private String transitionReason;  // WHY the orchestrator made this decision (e.g., "first_run", "reflection_round_2", "api_error_recovery")

    private boolean isRecovery;       // true if this decision is part of an error recovery path

    public static IncidentFixOrchestratorDecision call(String selectedSkill, String reason) {
        return IncidentFixOrchestratorDecision.builder()
                .decision("CALL_SKILL")
                .selectedSkill(selectedSkill)
                .reason(reason)
                .transitionReason("normal_progression")
                .isRecovery(false)
                .build();
    }

    public static IncidentFixOrchestratorDecision call(String selectedSkill, String reason,
                                                        String transitionReason, boolean isRecovery) {
        return IncidentFixOrchestratorDecision.builder()
                .decision("CALL_SKILL")
                .selectedSkill(selectedSkill)
                .reason(reason)
                .transitionReason(transitionReason)
                .isRecovery(isRecovery)
                .build();
    }

    public static IncidentFixOrchestratorDecision stop(String reason) {
        return IncidentFixOrchestratorDecision.builder()
                .decision("STOP")
                .reason(reason)
                .transitionReason("pipeline_complete")
                .isRecovery(false)
                .build();
    }

    public static IncidentFixOrchestratorDecision stop(String reason, String transitionReason) {
        return IncidentFixOrchestratorDecision.builder()
                .decision("STOP")
                .reason(reason)
                .transitionReason(transitionReason)
                .isRecovery(true)
                .build();
    }

    public boolean shouldStop() {
        return "STOP".equals(decision);
    }

}
