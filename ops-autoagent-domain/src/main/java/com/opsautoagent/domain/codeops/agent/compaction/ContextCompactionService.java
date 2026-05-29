package com.opsautoagent.domain.codeops.agent.compaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Context compaction service — inspired by Claude Code's 4-level compression pipeline.
 * Applied before each reflection round to keep LLM context under control.
 *
 * Levels (executed in order, cheapest first):
 *   1. TRUNCATE — truncate old tool outputs beyond a threshold
 *   2. SUMMARIZE — compress prior reflection rounds into a short summary
 *
 * For Java, we skip dedup/fold since our context is structured JSON, not raw messages.
 */
@Slf4j
@Service
public class ContextCompactionService {

    @Value("${codeops.compaction.max-tool-output-chars:3000}")
    private int maxToolOutputChars;

    @Value("${codeops.compaction.max-reflection-summary-chars:800}")
    private int maxReflectionSummaryChars;

    @Value("${codeops.compaction.enabled:true}")
    private boolean enabled;

    /**
     * Compact skill outputs before sending to LLM in a reflection round.
     * Returns a shallow copy with truncated tool outputs.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> compact(Map<String, Object> skillOutputs, int reflectionRound) {
        if (!enabled || skillOutputs == null || reflectionRound < 1) {
            return skillOutputs;
        }

        Map<String, Object> compacted = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : skillOutputs.entrySet()) {
            String skillId = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map<?, ?> skillMap) {
                Map<String, Object> cleaned = new LinkedHashMap<>();
                ((Map<String, Object>) skillMap).forEach((k, v) -> {
                    if (isPreserveField(k)) {
                        cleaned.put(k, v); // Keep critical error info in full
                    } else if (isTruncatableField(k)) {
                        cleaned.put(k, truncateValue(v));
                    } else {
                        cleaned.put(k, v);
                    }
                });

                // For bug_fix in prior reflection rounds, add a summary instead of raw output
                if ("bug_fix".equals(skillId) && reflectionRound > 0) {
                    Object rawOutput = cleaned.get("rawOutput");
                    if (rawOutput instanceof Map<?, ?> raw) {
                        Map<String, Object> compactRaw = compactBugFixOutput((Map<String, Object>) raw);
                        cleaned.put("rawOutput", compactRaw);
                        cleaned.put("_compacted", true);
                        cleaned.put("_compactionLevel", "SUMMARIZE");
                    }
                }

                compacted.put(skillId, cleaned);
            } else {
                compacted.put(skillId, value);
            }
        }

        int originalSize = estimateSize(skillOutputs);
        int compactedSize = estimateSize(compacted);
        log.info("Context compaction: {}→{} chars ({}%), round={}",
                originalSize, compactedSize,
                originalSize > 0 ? compactedSize * 100 / originalSize : 100,
                reflectionRound);

        return compacted;
    }

    /**
     * Build a compacted bug_fix output — keep rootCause/confidence/patchDraft,
     * drop full file contents and raw LLM output.
     */
    private Map<String, Object> compactBugFixOutput(Map<String, Object> raw) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("_compacted", true);
        for (String key : List.of("rootCause", "confidence", "patchDraft", "llmGenerated",
                "patchApply", "compileGate", "patchRolledBack", "patchScopeGuard")) {
            if (raw.containsKey(key)) {
                compact.put(key, raw.get(key));
            }
        }
        // Drop: codeSnippets, raw LLM content, full file rewrites
        return compact;
    }

    private boolean isTruncatableField(String fieldName) {
        // Safe to truncate: successful tool output, non-critical summaries
        return fieldName.equals("output")
                || fieldName.equals("testOutput")
                || fieldName.equals("diagnosisClues")
                || fieldName.equals("fixSuggestions")
                || fieldName.equals("verificationHints");
    }

    private boolean isPreserveField(String fieldName) {
        // Critical — preserve in full: error messages, failure summaries, compile output, guard violations
        return fieldName.equals("errorMessage")
                || fieldName.equals("rawFailureSummary")
                || fieldName.equals("summary")
                || fieldName.equals("compileGate")
                || fieldName.equals("patchScopeGuard")
                || fieldName.equals("sourceValidation")
                || fieldName.equals("patchValidation");
    }

    private Object truncateValue(Object value) {
        if (value == null) return "";
        String str = value instanceof String ? (String) value : String.valueOf(value);
        // Preserve error-related content: keep first 15000 chars (enough for ~200 compile error lines)
        if (str.length() <= maxToolOutputChars) return str;
        // Keep head AND tail — head has first errors, tail has summary/counts
        int keepHead = maxToolOutputChars * 3 / 4;
        int keepTail = maxToolOutputChars / 4;
        return str.substring(0, keepHead)
                + String.format("\n... [%d chars truncated, showing first %d + last %d] ...\n",
                    str.length() - maxToolOutputChars, keepHead, keepTail)
                + str.substring(str.length() - keepTail);
    }

    private int estimateSize(Map<String, Object> map) {
        int size = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            size += entry.getKey().length();
            if (entry.getValue() instanceof String s) {
                size += s.length();
            } else if (entry.getValue() instanceof Map<?, ?> m) {
                size += estimateSize((Map<String, Object>) m);
            }
        }
        return size;
    }
}
