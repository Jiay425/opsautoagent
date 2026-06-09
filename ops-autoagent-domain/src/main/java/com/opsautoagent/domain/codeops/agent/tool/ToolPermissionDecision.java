package com.opsautoagent.domain.codeops.agent.tool;

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
public class ToolPermissionDecision {

    private String toolName;

    private String status;

    private boolean allowed;

    private boolean requiresApproval;

    private String reason;

    @Builder.Default
    private Map<String, Object> policy = new LinkedHashMap<>();

    public static ToolPermissionDecision allow(String toolName, String reason, Map<String, Object> policy) {
        return ToolPermissionDecision.builder()
                .toolName(toolName)
                .status("ALLOWED")
                .allowed(true)
                .requiresApproval(false)
                .reason(reason)
                .policy(policy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(policy))
                .build();
    }

    public static ToolPermissionDecision deny(String toolName, String reason, Map<String, Object> policy) {
        return ToolPermissionDecision.builder()
                .toolName(toolName)
                .status("DENIED")
                .allowed(false)
                .requiresApproval(false)
                .reason(reason)
                .policy(policy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(policy))
                .build();
    }

    public static ToolPermissionDecision approvalRequired(String toolName, String reason, Map<String, Object> policy) {
        return ToolPermissionDecision.builder()
                .toolName(toolName)
                .status("REQUIRES_APPROVAL")
                .allowed(false)
                .requiresApproval(true)
                .reason(reason)
                .policy(policy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(policy))
                .build();
    }

}
