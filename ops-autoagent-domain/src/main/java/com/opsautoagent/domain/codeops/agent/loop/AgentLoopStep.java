package com.opsautoagent.domain.codeops.agent.loop;

import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolResult;
import com.opsautoagent.domain.codeops.agent.tool.ToolPermissionDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentLoopStep {

    private int turnNo;

    private String toolCallId;

    private String toolName;

    @Builder.Default
    private Map<String, Object> arguments = new LinkedHashMap<>();

    private ToolPermissionDecision permissionDecision;

    private EngineeringToolResult toolResult;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

}
