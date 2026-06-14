package com.opsautoagent.domain.codeops.agent.tool;

import com.opsautoagent.domain.codeops.agent.security.AgentPermissionPolicy;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringToolDefinitionEntity;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ToolPermissionGate {

    private final AgentPermissionPolicy permissionPolicy;

    public ToolPermissionGate(AgentPermissionPolicy permissionPolicy) {
        this.permissionPolicy = permissionPolicy;
    }

    public ToolPermissionDecision decide(EngineeringToolRequest request,
                                         Optional<EngineeringToolDefinitionEntity> definition) {
        String toolName = request == null ? "" : request.getToolName();
        Map<String, Object> policy = buildPolicy(request, definition);
        if (definition.isEmpty()) {
            return ToolPermissionDecision.deny(toolName, "Unknown tool: " + toolName, policy);
        }
        EngineeringToolDefinitionEntity tool = definition.get();
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            return ToolPermissionDecision.deny(toolName, "Tool is disabled: " + toolName, policy);
        }
        if (request != null && request.getExecutionContext() != null
                && request.getExecutionContext().getAllowedTools() != null
                && !request.getExecutionContext().getAllowedTools().isEmpty()
                && !request.getExecutionContext().getAllowedTools().contains(toolName)) {
            return ToolPermissionDecision.deny(toolName, "Tool is outside current skill constraints: " + toolName, policy);
        }
        ToolAccessLevel accessLevel = parseAccessLevel(tool.getAccessLevel());
        if (accessLevel == ToolAccessLevel.COMMAND_EXECUTE && !isCommandAllowed(request)) {
            return ToolPermissionDecision.deny(toolName, "Command is not allowed by AgentPermissionPolicy", policy);
        }
        if (accessLevel == ToolAccessLevel.SOURCE_WRITE && !isSourceWriteAllowed(request)) {
            return ToolPermissionDecision.deny(toolName, "Write target is outside allowed repository source scope", policy);
        }
        if (accessLevel == ToolAccessLevel.HIGH_RISK_WRITE || isHighRisk(tool.getRiskLevel())) {
            return ToolPermissionDecision.approvalRequired(toolName, "High-risk tool requires human approval", policy);
        }
        return ToolPermissionDecision.allow(toolName, "Allowed by tool registry and permission policy", policy);
    }

    private boolean isCommandAllowed(EngineeringToolRequest request) {
        if (request == null) {
            return false;
        }
        Object command = request.argument("command");
        if (command == null && request.argument("args") != null) {
            Object args = request.argument("args");
            if (args instanceof List<?> list) {
                command = "mvn " + String.join(" ", list.stream().map(String::valueOf).toList());
            } else {
                command = "mvn " + args;
            }
        }
        return permissionPolicy.isCommandAllowed(command == null ? "" : String.valueOf(command));
    }

    private boolean isSourceWriteAllowed(EngineeringToolRequest request) {
        if (request == null) {
            return false;
        }
        String repository = request.stringArgument("repository");
        String filePath = request.stringArgument("filePath");
        if (repository.isBlank() && request.getTask() != null) {
            repository = request.getTask().getRepository();
        }
        return permissionPolicy.isWriteAllowed(repository, filePath);
    }

    private Map<String, Object> buildPolicy(EngineeringToolRequest request,
                                            Optional<EngineeringToolDefinitionEntity> definition) {
        EngineeringTaskEntity task = request == null ? null : request.getTask();
        String repository = task == null ? "" : task.getRepository();
        String taskType = task == null ? "" : task.getTaskType();
        String severity = "";
        if (task != null && task.getContext() != null && task.getContext().get("severity") != null) {
            severity = String.valueOf(task.getContext().get("severity"));
        }
        Map<String, Object> policy = new LinkedHashMap<>(permissionPolicy.evaluate(repository, taskType, severity).toMap());
        definition.ifPresent(tool -> {
            policy.put("toolName", tool.getToolName());
            policy.put("toolAccessLevel", tool.getAccessLevel());
            policy.put("toolRiskLevel", tool.getRiskLevel());
            policy.put("toolSourceType", tool.getSourceType());
        });
        return policy;
    }

    private ToolAccessLevel parseAccessLevel(String accessLevel) {
        if (accessLevel == null || accessLevel.isBlank()) {
            return ToolAccessLevel.READ_ONLY;
        }
        try {
            return ToolAccessLevel.valueOf(accessLevel.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ToolAccessLevel.READ_ONLY;
        }
    }

    private boolean isHighRisk(String riskLevel) {
        return "HIGH".equalsIgnoreCase(riskLevel) || "CRITICAL".equalsIgnoreCase(riskLevel);
    }

}
