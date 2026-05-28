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
    private BigDecimal patchApplyRate;
    private BigDecimal compilePassRate;
    private BigDecimal testPassRate;
    private BigDecimal reflectionRecoveryRate;
    private BigDecimal noCodeFixAccuracy;

    public static CodeOpsEvalMetricSummary empty() {
        return builder()
                .scopeAccuracy(BigDecimal.ZERO)
                .patchApplyRate(BigDecimal.ZERO)
                .compilePassRate(BigDecimal.ZERO)
                .testPassRate(BigDecimal.ZERO)
                .reflectionRecoveryRate(BigDecimal.ZERO)
                .noCodeFixAccuracy(BigDecimal.ZERO)
                .build();
    }
}
