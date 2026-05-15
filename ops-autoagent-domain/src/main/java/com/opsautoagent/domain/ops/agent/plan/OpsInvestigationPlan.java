package com.opsautoagent.domain.ops.agent.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsInvestigationPlan {

    private String planId;

    private String diagnosisId;

    private String stateId;

    private Integer round;

    private String alertType;

    private String hypothesesJson;

    private String stepsJson;

    private String requiredToolsJson;

    private String expectedEvidenceJson;

    private String riskLevel;

    private String budgetJson;

    private String planJson;

    private String plannerType;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}

