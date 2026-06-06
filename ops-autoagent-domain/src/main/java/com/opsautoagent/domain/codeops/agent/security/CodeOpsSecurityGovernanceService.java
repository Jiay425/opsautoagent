package com.opsautoagent.domain.codeops.agent.security;

import com.opsautoagent.domain.codeops.agent.tool.ToolExecutionRecord;
import com.opsautoagent.domain.codeops.agent.tool.ToolRuntimeService;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CodeOpsSecurityGovernanceService {

    private final AgentPermissionPolicy permissionPolicy;
    private final ToolRuntimeService toolRuntimeService;

    public CodeOpsSecurityGovernanceService(AgentPermissionPolicy permissionPolicy,
                                            ToolRuntimeService toolRuntimeService) {
        this.permissionPolicy = permissionPolicy;
        this.toolRuntimeService = toolRuntimeService;
    }

    public Map<String, Object> globalSummary() {
        List<ToolExecutionRecord> recentTools = toolRuntimeService.listRecentRecords(100);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("permissionPolicy", permissionPolicy.governanceSummary());
        summary.put("toolAudit", toolAuditSummary(recentTools));
        summary.put("enterpriseControls", List.of(
                "API token and rate-limit guard for ops endpoints",
                "tool gateway call audit",
                "command allowlist and deny patterns",
                "repository-scoped write policy",
                "patch scope guard before apply",
                "sandbox workspace and rollback",
                "compile/test gates",
                "high-risk human approval",
                "secret redaction in tool traces"
        ));
        summary.put("recentDeniedTools", recentTools.stream()
                .filter(record -> record.getStatus() != null && "DENIED".equals(record.getStatus().name()))
                .limit(10)
                .map(this::compactToolRecord)
                .toList());
        return summary;
    }

    public Map<String, Object> taskSummary(EngineeringTaskEntity task, Map<String, Object> approval) {
        Map<String, Object> context = task == null || task.getContext() == null ? Map.of() : task.getContext();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskId", task == null ? "" : task.getTaskId());
        summary.put("status", task == null ? "" : task.getStatus());
        summary.put("permissionPolicy", permissionPolicy.evaluate(
                task == null ? "" : task.getRepository(),
                task == null ? "" : task.getTaskType(),
                stringValue(context.get("severity"))
        ).toMap());
        summary.put("guardrails", mapValue(context.get("guardrailSummary")));
        summary.put("approval", approval == null ? Map.of() : approval);
        summary.put("toolAudit", taskToolAudit(context));
        summary.put("riskPosture", riskPosture(summary));
        return summary;
    }

    private Map<String, Object> toolAuditSummary(List<ToolExecutionRecord> records) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recentCount", records.size());
        summary.put("deniedCount", records.stream().filter(record -> record.getStatus() != null
                && "DENIED".equals(record.getStatus().name())).count());
        summary.put("failedCount", records.stream().filter(record -> record.getStatus() != null
                && "FAILED".equals(record.getStatus().name())).count());
        summary.put("byTool", records.stream().collect(Collectors.groupingBy(
                ToolExecutionRecord::getToolName,
                LinkedHashMap::new,
                Collectors.counting()
        )));
        return summary;
    }

    private Map<String, Object> taskToolAudit(Map<String, Object> context) {
        Object value = context.get("toolRuntimeTrace");
        if (!(value instanceof List<?> records)) {
            return Map.of("total", 0, "denied", 0, "failed", 0);
        }
        long denied = records.stream().filter(item -> item instanceof Map<?, ?> map
                && "DENIED".equalsIgnoreCase(stringValue(map.get("status")))).count();
        long failed = records.stream().filter(item -> item instanceof Map<?, ?> map
                && "FAILED".equalsIgnoreCase(stringValue(map.get("status")))).count();
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("total", records.size());
        audit.put("denied", denied);
        audit.put("failed", failed);
        audit.put("recent", records.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .skip(Math.max(0, records.size() - 20))
                .toList());
        return audit;
    }

    private Map<String, Object> riskPosture(Map<String, Object> summary) {
        Map<String, Object> guardrails = mapValue(summary.get("guardrails"));
        Map<String, Object> approval = mapValue(summary.get("approval"));
        Map<String, Object> toolAudit = mapValue(summary.get("toolAudit"));
        boolean approvalPending = "PENDING".equalsIgnoreCase(stringValue(approval.get("status")));
        boolean staticSafetyPassed = Boolean.TRUE.equals(guardrails.get("patchStaticSafetyPassed"));
        boolean sandboxIsolated = Boolean.TRUE.equals(guardrails.get("patchSandboxIsolated"));
        long deniedTools = longValue(toolAudit.get("denied"));
        String level;
        if (approvalPending || deniedTools > 0) {
            level = "HIGH";
        } else if (!guardrails.isEmpty() && (!staticSafetyPassed || !sandboxIsolated)) {
            level = "MEDIUM";
        } else {
            level = "LOW";
        }
        Map<String, Object> posture = new LinkedHashMap<>();
        posture.put("level", level);
        posture.put("approvalPending", approvalPending);
        posture.put("staticSafetyPassed", staticSafetyPassed);
        posture.put("sandboxIsolated", sandboxIsolated);
        posture.put("deniedToolCalls", deniedTools);
        return posture;
    }

    private Map<String, Object> compactToolRecord(ToolExecutionRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("toolCallId", record.getToolCallId());
        item.put("taskId", record.getTaskId());
        item.put("toolName", record.getToolName());
        item.put("status", record.getStatus() == null ? "" : record.getStatus().name());
        item.put("errorType", record.getErrorType());
        item.put("costMillis", record.getCostMillis());
        return item;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
