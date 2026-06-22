package com.opsautoagent.domain.codeops.agent.hook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsHookResult {

    @Builder.Default
    private boolean allowed = true;

    @Builder.Default
    private boolean requiresApproval = false;

    private String reason;

    @Builder.Default
    private List<CodeOpsHookDecision> decisions = List.of();

    public Map<String, Object> toRawOutput() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("allowed", allowed);
        raw.put("requiresApproval", requiresApproval);
        raw.put("reason", reason == null ? "" : reason);
        raw.put("decisions", decisions == null ? List.of() : decisions.stream()
                .map(CodeOpsHookDecision::toRawOutput)
                .toList());
        return raw;
    }
}
