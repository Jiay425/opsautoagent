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
public class EngineeringToolResult {

    private String toolName;

    private boolean success;

    private String status;

    private String summary;

    private Object output;

    private String errorType;

    private String errorMessage;

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public static EngineeringToolResult success(String toolName, String summary, Object output) {
        return EngineeringToolResult.builder()
                .toolName(toolName)
                .success(true)
                .status("SUCCESS")
                .summary(summary)
                .output(output)
                .build();
    }

    public static EngineeringToolResult denied(String toolName, String reason) {
        return EngineeringToolResult.builder()
                .toolName(toolName)
                .success(false)
                .status("DENIED")
                .summary(reason)
                .errorType("PERMISSION_DENIED")
                .errorMessage(reason)
                .build();
    }

    public static EngineeringToolResult requiresApproval(String toolName, String reason, Map<String, Object> metadata) {
        return EngineeringToolResult.builder()
                .toolName(toolName)
                .success(false)
                .status("REQUIRES_APPROVAL")
                .summary(reason)
                .errorType("HUMAN_APPROVAL_REQUIRED")
                .errorMessage(reason)
                .metadata(metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata))
                .build();
    }

    public static EngineeringToolResult failed(String toolName, Exception error) {
        return EngineeringToolResult.builder()
                .toolName(toolName)
                .success(false)
                .status("FAILED")
                .summary(error == null ? "" : error.getMessage())
                .errorType(error == null ? "" : error.getClass().getSimpleName())
                .errorMessage(error == null ? "" : error.getMessage())
                .build();
    }

}
