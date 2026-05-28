package com.opsautoagent.domain.codeops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsEvalReport {

    private String batchId;
    private String runTime;
    private int totalCases;
    private int successCases;
    private int failedCases;
    private CodeOpsEvalMetricSummary summaryMetrics;
    private List<CodeOpsEvalCaseReport> cases;
    private List<CodeOpsEvalStepReport> pipelineTrace;
}
