package com.opsautoagent.domain.codeops.agent.recovery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Error recovery policy — inspired by Claude Code's 7 "continue sites".
 * Each recovery path handles a specific, recoverable error silently.
 *
 * Recovery sites:
 *   1. API_ERROR_FALLBACK — 401/402/429 → wait and retry (exponential backoff)
 *   2. PROMPT_TOO_LONG — context exceeds model limit → trigger compaction → retry
 *   3. TOKEN_BUDGET_CONTINUATION — max_tokens exhausted → inject continuation nudge
 */
@Slf4j
@Service
public class ErrorRecoveryPolicy {

    public RecoveryDecision evaluate(String errorMessage, int reflectionRound) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return RecoveryDecision.none();
        }

        String lower = errorMessage.toLowerCase();

        // Site 1: API errors — 401 invalid token, 402 insufficient balance, 429 rate limit
        if (lower.contains("401") || lower.contains("402") || lower.contains("429")
                || lower.contains("invalid token") || lower.contains("insufficient balance")
                || lower.contains("rate limit") || lower.contains("payment required")) {
            int waitSeconds = Math.min((int) Math.pow(2, reflectionRound + 1), 30);
            return RecoveryDecision.builder()
                    .recoverable(true)
                    .recoveryType("API_ERROR_FALLBACK")
                    .action("WAIT_AND_RETRY")
                    .waitSeconds(waitSeconds)
                    .reason("API error detected: " + truncate(errorMessage, 120)
                            + " — waiting " + waitSeconds + "s before retry")
                    .build();
        }

        // Site 2: Prompt too long — context exceeds model window
        if (lower.contains("prompt too long") || lower.contains("context length")
                || lower.contains("maximum context") || lower.contains("token limit")
                || lower.contains("413") || lower.contains("request too large")) {
            return RecoveryDecision.builder()
                    .recoverable(true)
                    .recoveryType("PROMPT_TOO_LONG")
                    .action("COMPACT_AND_RETRY")
                    .waitSeconds(1)
                    .reason("Context exceeds limit — triggering compaction before retry")
                    .build();
        }

        // Site 3: Token budget continuation — output cap hit
        if (lower.contains("max_tokens") || lower.contains("maximum tokens")
                || lower.contains("output length") || lower.contains("finish_reason")
                || lower.contains("blank content")) {
            return RecoveryDecision.builder()
                    .recoverable(true)
                    .recoveryType("TOKEN_BUDGET_CONTINUATION")
                    .action("CONTINUE_WITH_NUDGE")
                    .waitSeconds(0)
                    .reason("Token budget exhausted — injecting continuation prompt")
                    .build();
        }

        // JSON parse errors — often transient LLM output quality issues
        if (lower.contains("jsonexception") || lower.contains("invalid escape")
                || lower.contains("parse error") || lower.contains("blank content")) {
            return RecoveryDecision.builder()
                    .recoverable(true)
                    .recoveryType("JSON_PARSE_RETRY")
                    .action("RETRY_WITH_CLEANER_PROMPT")
                    .waitSeconds(1)
                    .reason("LLM JSON parse failure — retrying with cleaner prompt constraints")
                    .build();
        }

        return RecoveryDecision.none();
    }

    /**
     * Whether the orchestrator should skip the current skill and move to the next
     * instead of retrying (e.g., for persistent API errors after max retries).
     */
    public boolean shouldSkipToReleaseRisk(int totalFailures, String lastError) {
        if (totalFailures >= 3) return true;
        if (lastError != null && lastError.toLowerCase().contains("402")) return true;
        return false;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }
}
