package com.opsautoagent.domain.codeops.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes LLM calls to flash (cheap/fast) or pro (capable/expensive) based on incident complexity.
 *
 * Heuristics:
 *   flash  → simple single-method fixes, NO_CODE_FIX, first attempts
 *   pro    → complex multi-method fixes, reflection rounds, concurrency/transaction incidents
 *
 * Escalation: flash fails 2 rounds → escalate to pro.
 */
@Slf4j
@Service
public class ModelRouter {

    @Value("${codeops.agent.llm.model.flash:deepseek-v4-flash}")
    private String flashModel;

    @Value("${codeops.agent.llm.model.pro:deepseek-v4-pro}")
    private String proModel;

    @Value("${codeops.agent.llm.model.flash-max-tokens:8192}")
    private int flashMaxTokens;

    @Value("${codeops.agent.llm.model.pro-max-tokens:16384}")
    private int proMaxTokens;

    private long flashCount;
    private long proCount;
    private long escalationCount;

    /**
     * Decide which model to use.
     */
    public ModelDecision decide(Map<String, Object> repairScope, String goal,
                                 int reflectionRound, int previousFlashFailures) {
        String scopeType = repairScope != null
                ? String.valueOf(repairScope.getOrDefault("scopeType", "")) : "";

        // Rule 1: flash failed 2+ rounds → escalate to pro
        if (previousFlashFailures >= 2) {
            escalationCount++;
            proCount++;
            return ModelDecision.pro(proModel, proMaxTokens,
                    "flash failed " + previousFlashFailures + " rounds, escalating to pro");
        }

        // Rule 2: reflection round → pro (needs nuance to fix previous errors)
        if (reflectionRound > 0) {
            proCount++;
            return ModelDecision.pro(proModel, proMaxTokens,
                    "reflection round " + reflectionRound + ", using pro for error recovery");
        }

        // Rule 3: NO_CODE_FIX → flash (no patch needed, just diagnosis)
        if ("NO_CODE_FIX".equals(scopeType)) {
            flashCount++;
            return ModelDecision.flash(flashModel, flashMaxTokens,
                    "NO_CODE_FIX — flash sufficient for runtime/config diagnosis");
        }

        // Rule 4: FULL_FILE → pro (broad incident, many methods)
        if ("FULL_FILE".equals(scopeType)) {
            proCount++;
            return ModelDecision.pro(proModel, proMaxTokens,
                    "FULL_FILE scope — pro needed for broad incident analysis");
        }

        // Rule 5: MULTI_METHOD → pro (2-3 methods, moderate complexity)
        if ("MULTI_METHOD".equals(scopeType)) {
            proCount++;
            return ModelDecision.pro(proModel, proMaxTokens,
                    "MULTI_METHOD scope — pro for coordinated multi-method fix");
        }

        // Rule 6: keyword check on goal text
        String lower = goal != null ? goal.toLowerCase() : "";
        if (lower.contains("concurency") || lower.contains("concurrency")
                || lower.contains("并发") || lower.contains("竞态")
                || lower.contains("race") || lower.contains("idempotent") || lower.contains("幂等")
                || lower.contains("transaction") || lower.contains("事务")
                || lower.contains("deadlock") || lower.contains("死锁")) {
            proCount++;
            return ModelDecision.pro(proModel, proMaxTokens,
                    "complex incident keywords detected — routing to pro");
        }

        // Default: flash for simple incidents (NPE, null check, single method)
        flashCount++;
        return ModelDecision.flash(flashModel, flashMaxTokens,
                "STRICT_SINGLE_METHOD or simple incident — flash sufficient");
    }

    /**
     * Decide for test verification (always flash — tests are straightforward).
     */
    public ModelDecision decideForTest() {
        return ModelDecision.flash(flashModel, 4096, "test verification — always flash");
    }

    /**
     * Decide for release risk analysis.
     * Use pro when reflection was exhausted (complex failure chain to summarize).
     */
    public ModelDecision decideForReleaseRisk(boolean reflectionExhausted) {
        if (reflectionExhausted) {
            proCount++;
            return ModelDecision.pro(proModel, proMaxTokens,
                    "reflection exhausted — pro needed for failure chain summary and manual handoff");
        }
        flashCount++;
        return ModelDecision.flash(flashModel, 4096, "release risk analysis — flash sufficient");
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("flashCalls", flashCount);
        stats.put("proCalls", proCount);
        stats.put("escalations", escalationCount);
        long total = flashCount + proCount;
        stats.put("totalCalls", total);
        stats.put("flashRatio", total > 0 ? flashCount * 100 / total + "%" : "N/A");
        stats.put("proRatio", total > 0 ? proCount * 100 / total + "%" : "N/A");
        return stats;
    }

    public static class ModelDecision {
        private final String model;
        private final int maxTokens;
        private final String reason;

        private ModelDecision(String model, int maxTokens, String reason) {
            this.model = model;
            this.maxTokens = maxTokens;
            this.reason = reason;
        }

        public static ModelDecision flash(String model, int maxTokens, String reason) {
            log.info("ModelRouter → flash ({}): {}", model, reason);
            return new ModelDecision(model, maxTokens, reason);
        }

        public static ModelDecision pro(String model, int maxTokens, String reason) {
            log.info("ModelRouter → pro ({}): {}", model, reason);
            return new ModelDecision(model, maxTokens, reason);
        }

        public String getModel() { return model; }
        public int getMaxTokens() { return maxTokens; }
        public String getReason() { return reason; }
    }
}
