package com.opsautoagent.domain.codeops.agent.recovery;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RecoveryDecision {

    private boolean recoverable;
    private String recoveryType;    // API_ERROR_FALLBACK | PROMPT_TOO_LONG | TOKEN_BUDGET_CONTINUATION | JSON_PARSE_RETRY
    private String action;          // WAIT_AND_RETRY | COMPACT_AND_RETRY | CONTINUE_WITH_NUDGE | RETRY_WITH_CLEANER_PROMPT
    private int waitSeconds;
    private String reason;

    public static RecoveryDecision none() {
        return RecoveryDecision.builder()
                .recoverable(false)
                .recoveryType("NONE")
                .action("FAIL")
                .waitSeconds(0)
                .reason("Non-recoverable error")
                .build();
    }
}
