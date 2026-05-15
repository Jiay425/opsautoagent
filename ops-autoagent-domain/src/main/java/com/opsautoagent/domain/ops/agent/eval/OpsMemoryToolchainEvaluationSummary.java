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
public class OpsMemoryToolchainEvaluationSummary {

    private String evaluationId;

    private Integer historicalMemoryCards;

    private BigDecimal historicalMemoryHitRate;

    private List<String> comparisonModes;

    private Map<String, Object> memoryCapabilities;

    private Map<String, Object> toolchainCapabilities;

    private Map<String, Object> evaluationMetrics;

    private String explanation;

}

