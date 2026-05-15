package com.opsautoagent.domain.ops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsEvaluationSummary {

    private String batchId;

    private Integer totalCases;

    private Integer successCases;

    private Integer failedCases;

    private BigDecimal top1RootCauseHitRate;

    private BigDecimal top3RootCauseHitRate;

    private BigDecimal averageEvidenceCoverage;

    private BigDecimal averageExpectedToolCoverage;

    private BigDecimal averageToolCallCount;

    private BigDecimal averageLatencyMs;

    private List<OpsEvalRun> runs;

}

