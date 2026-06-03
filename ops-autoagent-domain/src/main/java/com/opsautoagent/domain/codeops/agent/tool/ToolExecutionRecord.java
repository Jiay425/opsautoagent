package com.opsautoagent.domain.codeops.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolExecutionRecord {

    private String toolCallId;

    private String taskId;

    private String traceId;

    private String executionId;

    private String agentOrSkill;

    private String toolName;

    private String logicalToolName;

    private String category;

    private ToolAccessLevel accessLevel;

    private ToolSourceType sourceType;

    private ToolExecutionStatus status;

    private Boolean success;

    private String requestSummary;

    private String responseSummary;

    private String errorType;

    private String errorMessage;

    private Long costMillis;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Map<String, Object> metadata;

}
