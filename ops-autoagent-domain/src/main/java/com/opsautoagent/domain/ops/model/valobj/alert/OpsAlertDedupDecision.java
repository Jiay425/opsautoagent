package com.opsautoagent.domain.ops.model.valobj.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsAlertDedupDecision {

    private String dedupKey;

    private boolean accepted;

    private String skipReason;

    public static OpsAlertDedupDecision accepted(String dedupKey) {
        return OpsAlertDedupDecision.builder()
                .dedupKey(dedupKey)
                .accepted(true)
                .build();
    }

    public static OpsAlertDedupDecision skipped(String dedupKey, String skipReason) {
        return OpsAlertDedupDecision.builder()
                .dedupKey(dedupKey)
                .accepted(false)
                .skipReason(skipReason)
                .build();
    }

}

