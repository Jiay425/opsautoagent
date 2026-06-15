package com.opsautoagent.domain.codeops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsEvalMetricSummary {

    private BigDecimal scopeAccuracy;
    private BigDecimal localizationDecisionAccuracy;
    private BigDecimal localizationTargetFileHitRate;
    private BigDecimal localizationTargetMethodHitRate;
    private BigDecimal localizationFixStrategyAccuracy;
    private BigDecimal localizationScopeDecisionAccuracy;
    private BigDecimal patchApplyRate;
    private BigDecimal compilePassRate;
    private BigDecimal testPassRate;
    private BigDecimal reflectionRecoveryRate;
    private BigDecimal noCodeFixAccuracy;
    private BigDecimal realEvidenceCoverageRate;
    private BigDecimal patchStaticSafetyRate;
    private BigDecimal patchSandboxIsolationRate;

    public static CodeOpsEvalMetricSummary empty() {
        return builder()
                .scopeAccuracy(BigDecimal.ZERO)
                .localizationDecisionAccuracy(BigDecimal.ZERO)
                .localizationTargetFileHitRate(BigDecimal.ZERO)
                .localizationTargetMethodHitRate(BigDecimal.ZERO)
                .localizationFixStrategyAccuracy(BigDecimal.ZERO)
                .localizationScopeDecisionAccuracy(BigDecimal.ZERO)
                .patchApplyRate(BigDecimal.ZERO)
                .compilePassRate(BigDecimal.ZERO)
                .testPassRate(BigDecimal.ZERO)
                .reflectionRecoveryRate(BigDecimal.ZERO)
                .noCodeFixAccuracy(BigDecimal.ZERO)
                .realEvidenceCoverageRate(BigDecimal.ZERO)
                .patchStaticSafetyRate(BigDecimal.ZERO)
                .patchSandboxIsolationRate(BigDecimal.ZERO)
                .build();
    }
}
