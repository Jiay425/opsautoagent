package com.opsautoagent.domain.codeops.agent.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LlmCostControlService {

    @Value("${codeops.agent.llm.cost.flash-input-per-1k:0.001}")
    private BigDecimal flashInputPer1k;

    @Value("${codeops.agent.llm.cost.flash-output-per-1k:0.002}")
    private BigDecimal flashOutputPer1k;

    @Value("${codeops.agent.llm.cost.pro-input-per-1k:0.01}")
    private BigDecimal proInputPer1k;

    @Value("${codeops.agent.llm.cost.pro-output-per-1k:0.02}")
    private BigDecimal proOutputPer1k;

    @Value("${codeops.agent.llm.cost.single-call-soft-limit-cny:1.00}")
    private BigDecimal singleCallSoftLimitCny;

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong totalEstimatedTokens = new AtomicLong();
    private final AtomicLong totalEstimatedMicroCny = new AtomicLong();
    private volatile Map<String, Object> lastCall = Map.of();

    public Map<String, Object> estimate(String agentOrSkill, String model, String prompt, String response) {
        int inputTokens = estimateTokens(prompt);
        int outputTokens = estimateTokens(response);
        String tier = isPro(model) ? "pro" : "flash";
        BigDecimal inputCost = costFor(inputTokens, isPro(model) ? proInputPer1k : flashInputPer1k);
        BigDecimal outputCost = costFor(outputTokens, isPro(model) ? proOutputPer1k : flashOutputPer1k);
        BigDecimal totalCost = inputCost.add(outputCost).setScale(6, RoundingMode.HALF_UP);
        boolean overSoftLimit = totalCost.compareTo(singleCallSoftLimitCny) > 0;

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("agentOrSkill", value(agentOrSkill));
        usage.put("model", value(model));
        usage.put("modelTier", tier);
        usage.put("estimatedInputTokens", inputTokens);
        usage.put("estimatedOutputTokens", outputTokens);
        usage.put("estimatedTotalTokens", inputTokens + outputTokens);
        usage.put("estimatedInputCostCny", inputCost);
        usage.put("estimatedOutputCostCny", outputCost);
        usage.put("estimatedTotalCostCny", totalCost);
        usage.put("singleCallSoftLimitCny", singleCallSoftLimitCny);
        usage.put("overSoftLimit", overSoftLimit);
        usage.put("recordedAt", LocalDateTime.now().toString());

        totalCalls.incrementAndGet();
        totalEstimatedTokens.addAndGet(inputTokens + outputTokens);
        totalEstimatedMicroCny.addAndGet(totalCost.movePointRight(6).longValue());
        lastCall = usage;
        return usage;
    }

    public Map<String, Object> globalSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalLlmCalls", totalCalls.get());
        summary.put("totalEstimatedTokens", totalEstimatedTokens.get());
        summary.put("totalEstimatedCostCny", BigDecimal.valueOf(totalEstimatedMicroCny.get(), 6)
                .setScale(4, RoundingMode.HALF_UP));
        summary.put("singleCallSoftLimitCny", singleCallSoftLimitCny);
        summary.put("lastCall", lastCall);
        summary.put("pricingNote", "Estimated by character/4 token approximation; provider billing may differ.");
        return summary;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    private BigDecimal costFor(int tokens, BigDecimal pricePer1k) {
        return BigDecimal.valueOf(tokens)
                .multiply(pricePer1k == null ? BigDecimal.ZERO : pricePer1k)
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    private boolean isPro(String model) {
        return model != null && model.toLowerCase().contains("pro");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
