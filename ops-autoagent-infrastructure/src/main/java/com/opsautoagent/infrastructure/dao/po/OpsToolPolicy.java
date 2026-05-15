package com.opsautoagent.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsToolPolicy {

    private Long id;

    private String toolName;

    private String agentRole;

    private Integer enabled;

    private Integer maxCallsPerDiagnosis;

    private Integer timeoutSeconds;

    private String requiredSeverity;

    private Integer allowAutoExecute;

    private Integer requiresApproval;

    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}

