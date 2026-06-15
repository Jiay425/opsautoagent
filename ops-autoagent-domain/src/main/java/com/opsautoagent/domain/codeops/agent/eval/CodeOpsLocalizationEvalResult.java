package com.opsautoagent.domain.codeops.agent.eval;

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
public class CodeOpsLocalizationEvalResult {

    private BigDecimal score;

    private Boolean fixStrategyMatched;

    private Boolean scopeDecisionMatched;

    private Boolean targetFileMatched;

    private Boolean targetMethodMatched;

    private Boolean shouldEnterCodeRepairMatched;

    private List<String> expectedTargetFiles;

    private List<String> actualTargetFiles;

    private List<String> missingTargetFiles;

    private List<String> expectedTargetMethods;

    private List<String> actualTargetMethods;

    private List<String> missingTargetMethods;

    private String expectedFixStrategy;

    private String actualFixStrategy;

    private String expectedScopeDecision;

    private String actualScopeDecision;

    private Boolean expectedShouldEnterCodeRepair;

    private Boolean actualShouldEnterCodeRepair;

    private Map<String, Object> rawDecision;
}
