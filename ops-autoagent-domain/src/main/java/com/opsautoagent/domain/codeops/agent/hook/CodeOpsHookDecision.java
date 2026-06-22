package com.opsautoagent.domain.codeops.agent.hook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsHookDecision {

    private String handlerName;

    @Builder.Default
    private boolean allowed = true;

    @Builder.Default
    private boolean requiresApproval = false;

    private String reason;

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public static CodeOpsHookDecision allow(String handlerName, String reason) {
        return CodeOpsHookDecision.builder()
                .handlerName(handlerName)
                .allowed(true)
                .requiresApproval(false)
                .reason(reason == null ? "" : reason)
                .build();
    }

    public static CodeOpsHookDecision deny(String handlerName, String reason) {
        return CodeOpsHookDecision.builder()
                .handlerName(handlerName)
                .allowed(false)
                .requiresApproval(false)
                .reason(reason == null ? "" : reason)
                .build();
    }

    public static CodeOpsHookDecision approvalRequired(String handlerName, String reason, Map<String, Object> metadata) {
        return CodeOpsHookDecision.builder()
                .handlerName(handlerName)
                .allowed(false)
                .requiresApproval(true)
                .reason(reason == null ? "" : reason)
                .metadata(metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata))
                .build();
    }

    public Map<String, Object> toRawOutput() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("handlerName", handlerName == null ? "" : handlerName);
        raw.put("allowed", allowed);
        raw.put("requiresApproval", requiresApproval);
        raw.put("reason", reason == null ? "" : reason);
        raw.put("metadata", metadata == null ? Map.of() : metadata);
        return raw;
    }
}
