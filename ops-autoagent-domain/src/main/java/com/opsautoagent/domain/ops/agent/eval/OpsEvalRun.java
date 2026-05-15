package com.opsautoagent.domain.ops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsEvalRun {

    private Long id;

    private String runId;

    private String caseId;

    private String diagnosisId;

    private String status;

    private Integer top1RootCauseHit;

    private Integer top3RootCauseHit;

    private BigDecimal requiredEvidenceCoverage;

    private Integer unsupportedConclusionCount;

    private Integer toolCallCount;

    private Long diagnosisLatencyMs;

    private String finalStatus;

    private String summaryJson;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}

