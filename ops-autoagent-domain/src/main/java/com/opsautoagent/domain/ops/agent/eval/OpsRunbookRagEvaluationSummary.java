package com.opsautoagent.domain.ops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsRunbookRagEvaluationSummary {

    private String batchId;

    private String mode;

    private Integer totalCases;

    private Integer successCases;

    private Integer failedCases;

    private BigDecimal top1Recall;

    private BigDecimal top3Recall;

    private BigDecimal top5Recall;

    private BigDecimal meanReciprocalRank;

    private BigDecimal averageLatencyMs;

    private BigDecimal rootCauseHitRate;

    private String reportJsonPath;

    private String reportMarkdownPath;

    private String failureCasesPath;

    private List<OpsRunbookRagEvalRun> runs;

    private Map<String, OpsRunbookRagEvaluationSummary> ablationSummaries;

}

