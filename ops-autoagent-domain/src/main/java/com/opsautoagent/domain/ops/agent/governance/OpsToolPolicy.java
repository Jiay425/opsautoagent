package com.opsautoagent.domain.ops.agent.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsToolPolicy {

    private String toolName;

    private String agentRole;

    private Boolean enabled;

    private Integer maxCallsPerDiagnosis;

    private Integer timeoutSeconds;

    private String requiredSeverity;

    private Boolean allowAutoExecute;

    private Boolean requiresApproval;

    private String description;

}

