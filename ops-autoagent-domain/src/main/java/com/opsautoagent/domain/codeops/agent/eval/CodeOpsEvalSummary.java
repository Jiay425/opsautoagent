package com.opsautoagent.domain.codeops.agent.eval;

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
public class CodeOpsEvalSummary {

    private String batchId;

    private Integer totalCases;

    private Integer successCases;

    private Integer failedCases;

    private BigDecimal averageExpectedSkillCoverage;

    private BigDecimal averageEvidenceKeywordCoverage;

    private BigDecimal averageArtifactCoverage;

    private BigDecimal averageCodeLocalizationCoverage;

    private BigDecimal averageLocalizationDecisionCoverage;

    private BigDecimal averageLocalizationTargetFileHitRate;

    private BigDecimal averageLocalizationTargetMethodHitRate;

    private BigDecimal averageLocalizationFixStrategyAccuracy;

    private BigDecimal averageLocalizationScopeDecisionAccuracy;

    private BigDecimal averagePatchCoverage;

    private BigDecimal averageTestCoverage;

    private BigDecimal averageRiskCoverage;

    private BigDecimal averageStepCount;

    private BigDecimal averageToolCallCount;

    private BigDecimal averageLatencyMs;

    private List<CodeOpsEvalRun> runs;

    private String reportJsonPath;

    private String reportMarkdownPath;

}
