package com.opsautoagent.domain.ops.agent.governance;

import com.opsautoagent.domain.ops.adapter.repository.IOpsGovernanceRepository;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.service.execute.DefaultOpsAgentExecuteStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpsToolGovernanceService {

    private static final String TOTAL_CALLS_KEY = "ops_governance_total_tool_calls";
    private static final String TOOL_COUNTS_KEY = "ops_governance_tool_call_counts";

    @Value("${ops.agent.tool-policy.enabled:true}")
    private boolean toolPolicyEnabled;

    @Value("${ops.agent.max-tool-calls:12}")
    private int maxToolCalls;

    @Value("${ops.agent.tool-policy.max-repeat-per-tool:2}")
    private int maxRepeatPerTool;

    @Value("${ops.agent.tool-policy.disabled-tools:}")
    private String disabledTools;

    @Resource
    private IOpsGovernanceRepository opsGovernanceRepository;

    public OpsToolGovernanceDecision evaluate(IncidentCommandEntity command,
                                              DefaultOpsAgentExecuteStrategyFactory.DynamicContext context,
                                              String toolName,
                                              String agentRole,
                                              String intent) {
        if (!toolPolicyEnabled || context == null || isBlank(toolName)) {
            return OpsToolGovernanceDecision.allowed(toolName, agentRole, "tool policy disabled", currentTotal(context), currentCount(context, toolName));
        }

        String normalizedToolName = normalize(toolName);
        Map<String, Integer> counts = toolCounts(context);
        int total = currentTotal(context);
        int toolCount = counts.getOrDefault(normalizedToolName, 0);

        OpsToolPolicy dbPolicy = queryDbPolicy(normalizedToolName, agentRole);
        if (dbPolicy != null) {
            return evaluateDbPolicy(command, context, toolName, agentRole, intent, normalizedToolName, dbPolicy, counts, total, toolCount);
        }

        if (disabledToolSet().contains(normalizedToolName)) {
            return OpsToolGovernanceDecision.denied(toolName, agentRole,
                    "tool disabled by ops.agent.tool-policy.disabled-tools: " + normalizedToolName,
                    total, toolCount);
        }
        if (total >= Math.max(1, maxToolCalls)) {
            return OpsToolGovernanceDecision.denied(toolName, agentRole,
                    "tool-call budget exceeded: " + total + "/" + Math.max(1, maxToolCalls),
                    total, toolCount);
        }
        if (toolCount >= Math.max(1, maxRepeatPerTool)) {
            return OpsToolGovernanceDecision.denied(toolName, agentRole,
                    "repeat limit exceeded for " + normalizedToolName + ": " + toolCount + "/" + Math.max(1, maxRepeatPerTool),
                    total, toolCount);
        }

        total++;
        toolCount++;
        counts.put(normalizedToolName, toolCount);
        context.setValue(TOTAL_CALLS_KEY, total);
        context.setValue(TOOL_COUNTS_KEY, counts);
        String reason = "allowed by tool policy. intent=" + blankToDefault(intent, "not provided")
                + ", total=" + total + "/" + Math.max(1, maxToolCalls)
                + ", toolCount=" + toolCount + "/" + Math.max(1, maxRepeatPerTool);
        return OpsToolGovernanceDecision.allowed(toolName, agentRole, reason, total, toolCount);
    }

    private OpsToolGovernanceDecision evaluateDbPolicy(IncidentCommandEntity command,
                                                       DefaultOpsAgentExecuteStrategyFactory.DynamicContext context,
                                                       String toolName,
                                                       String agentRole,
                                                       String intent,
                                                       String normalizedToolName,
                                                       OpsToolPolicy policy,
                                                       Map<String, Integer> counts,
                                                       int total,
                                                       int toolCount) {
        int maxCalls = Math.max(1, value(policy.getMaxCallsPerDiagnosis(), maxRepeatPerTool));
        int timeoutSeconds = Math.max(1, value(policy.getTimeoutSeconds(), 30));
        String policyRole = blankToDefault(policy.getAgentRole(), "*");

        if (!Boolean.TRUE.equals(policy.getEnabled())) {
            return OpsToolGovernanceDecision.denied(toolName, agentRole,
                    "tool disabled by ops_tool_policy: " + normalizedToolName + ", policyRole=" + policyRole,
                    total, toolCount);
        }
        if (toolCount >= maxCalls) {
            return OpsToolGovernanceDecision.denied(toolName, agentRole,
                    "DB policy maxCallsPerDiagnosis exceeded for " + normalizedToolName + ": " + toolCount + "/" + maxCalls
                            + ", policyRole=" + policyRole,
                    total, toolCount);
        }
        String incidentSeverity = resolveIncidentSeverity(command);
        String requiredSeverity = normalizeSeverity(policy.getRequiredSeverity());
        if (!isBlank(requiredSeverity) && severityRank(incidentSeverity) > severityRank(requiredSeverity)) {
            return OpsToolGovernanceDecision.denied(toolName, agentRole,
                    "incident severity " + incidentSeverity + " is lower than requiredSeverity " + requiredSeverity
                            + " for " + normalizedToolName + ", policyRole=" + policyRole,
                    total, toolCount);
        }
        if (!Boolean.TRUE.equals(policy.getAllowAutoExecute())) {
            return OpsToolGovernanceDecision.denied(toolName, agentRole,
                    "tool auto execution disabled by ops_tool_policy: " + normalizedToolName + ", policyRole=" + policyRole,
                    total, toolCount);
        }
        if (Boolean.TRUE.equals(policy.getRequiresApproval())) {
            return OpsToolGovernanceDecision.denied(toolName, agentRole,
                    "tool requires manual approval by ops_tool_policy: " + normalizedToolName + ", policyRole=" + policyRole,
                    total, toolCount);
        }

        total++;
        toolCount++;
        counts.put(normalizedToolName, toolCount);
        context.setValue(TOTAL_CALLS_KEY, total);
        context.setValue(TOOL_COUNTS_KEY, counts);
        String reason = "allowed by DB tool policy. intent=" + blankToDefault(intent, "not provided")
                + ", policyRole=" + policyRole
                + ", severity=" + incidentSeverity
                + ", timeoutSeconds=" + timeoutSeconds
                + ", toolCount=" + toolCount + "/" + maxCalls;
        return OpsToolGovernanceDecision.allowed(toolName, agentRole, reason, total, toolCount);
    }

    private OpsToolPolicy queryDbPolicy(String normalizedToolName, String agentRole) {
        try {
            return opsGovernanceRepository.queryToolPolicy(normalizedToolName, blankToDefault(agentRole, "*"));
        } catch (Exception e) {
            log.warn("Query ops tool policy failed, fallback to config. toolName={}, agentRole={}, error={}",
                    normalizedToolName, agentRole, e.getMessage());
            return null;
        }
    }

    private int currentTotal(DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        Integer total = context == null ? null : context.getValue(TOTAL_CALLS_KEY);
        return total == null ? 0 : total;
    }

    private int currentCount(DefaultOpsAgentExecuteStrategyFactory.DynamicContext context, String toolName) {
        if (context == null || isBlank(toolName)) {
            return 0;
        }
        return toolCounts(context).getOrDefault(normalize(toolName), 0);
    }

    private Map<String, Integer> toolCounts(DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        Map<String, Integer> counts = context.getValue(TOOL_COUNTS_KEY);
        if (counts == null) {
            counts = new LinkedHashMap<>();
            context.setValue(TOOL_COUNTS_KEY, counts);
        }
        return counts;
    }

    private Set<String> disabledToolSet() {
        if (isBlank(disabledTools)) {
            return new HashSet<>();
        }
        return Arrays.stream(disabledTools.split(","))
                .map(this::normalize)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toSet());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private int value(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String resolveIncidentSeverity(IncidentCommandEntity command) {
        if (command == null || isBlank(command.getProblem())) {
            return "P2";
        }
        String text = command.getProblem().toUpperCase(Locale.ROOT);
        if (text.contains("P1") || text.contains("CRITICAL") || text.contains("FATAL") || text.contains("EMERGENCY")) {
            return "P1";
        }
        if (text.contains("P2") || text.contains("WARNING") || text.contains("WARN") || text.contains("ERROR") || text.contains("HIGH")) {
            return "P2";
        }
        if (text.contains("P3") || text.contains("INFO") || text.contains("NOTICE") || text.contains("MEDIUM")) {
            return "P3";
        }
        if (text.contains("P4") || text.contains("LOW")) {
            return "P4";
        }
        return "P2";
    }

    private String normalizeSeverity(String severity) {
        if (isBlank(severity)) {
            return "";
        }
        String normalized = severity.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CRITICAL", "FATAL", "EMERGENCY" -> "P1";
            case "WARNING", "WARN", "ERROR", "HIGH" -> "P2";
            case "INFO", "NOTICE", "MEDIUM" -> "P3";
            case "LOW" -> "P4";
            default -> normalized;
        };
    }

    private int severityRank(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "P1" -> 1;
            case "P2" -> 2;
            case "P3" -> 3;
            case "P4" -> 4;
            default -> 2;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

