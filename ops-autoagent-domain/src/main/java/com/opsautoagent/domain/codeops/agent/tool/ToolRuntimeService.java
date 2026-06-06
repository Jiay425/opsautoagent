package com.opsautoagent.domain.codeops.agent.tool;

import com.opsautoagent.domain.codeops.agent.runtime.AgentExecutionContext;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class ToolRuntimeService {

    private static final int MAX_TASK_TOOL_TRACE = 200;
    private static final int MAX_RECENT_RECORDS = 500;
    private static final ThreadLocal<ToolRuntimeScope> CURRENT_SCOPE = new ThreadLocal<>();

    private final Deque<ToolExecutionRecord> recentRecords = new ConcurrentLinkedDeque<>();

    public void bind(EngineeringTaskEntity task, AgentExecutionContext context) {
        if (task == null || context == null) {
            CURRENT_SCOPE.remove();
            return;
        }
        CURRENT_SCOPE.set(ToolRuntimeScope.builder()
                .task(task)
                .taskId(task.getTaskId())
                .traceId(context.getTraceId())
                .executionId(context.getExecutionId())
                .agentOrSkill(context.getSelectedSkill())
                .build());
    }

    public void clear() {
        CURRENT_SCOPE.remove();
    }

    public ToolExecutionRecord begin(String toolName,
                                     String logicalToolName,
                                     String category,
                                     ToolAccessLevel accessLevel,
                                     ToolSourceType sourceType,
                                     String requestSummary,
                                     Map<String, Object> metadata) {
        ToolRuntimeScope scope = CURRENT_SCOPE.get();
        return ToolExecutionRecord.builder()
                .toolCallId(UUID.randomUUID().toString())
                .taskId(scope == null ? "" : scope.taskId)
                .traceId(scope == null ? "" : scope.traceId)
                .executionId(scope == null ? "" : scope.executionId)
                .agentOrSkill(scope == null ? "" : scope.agentOrSkill)
                .toolName(toolName)
                .logicalToolName(logicalToolName)
                .category(category)
                .accessLevel(accessLevel)
                .sourceType(sourceType)
                .status(ToolExecutionStatus.RUNNING)
                .success(false)
                .requestSummary(safeSummary(requestSummary))
                .metadata(copy(metadata))
                .startTime(LocalDateTime.now())
                .build();
    }

    public ToolExecutionRecord success(ToolExecutionRecord record, String responseSummary) {
        return finish(record, ToolExecutionStatus.SUCCESS, true, responseSummary, "", "");
    }

    public ToolExecutionRecord denied(ToolExecutionRecord record, String responseSummary) {
        return finish(record, ToolExecutionStatus.DENIED, false, responseSummary, "PERMISSION_DENIED", responseSummary);
    }

    public ToolExecutionRecord timeout(ToolExecutionRecord record, String responseSummary) {
        return finish(record, ToolExecutionStatus.TIMEOUT, false, responseSummary, "TIMEOUT", responseSummary);
    }

    public ToolExecutionRecord failure(ToolExecutionRecord record, Exception error) {
        String errorType = error == null ? "" : error.getClass().getSimpleName();
        String errorMessage = error == null ? "" : error.getMessage();
        return finish(record, ToolExecutionStatus.FAILED, false, errorMessage, errorType, errorMessage);
    }

    public List<ToolExecutionRecord> listRecentRecords(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_RECENT_RECORDS));
        List<ToolExecutionRecord> records = new ArrayList<>();
        for (ToolExecutionRecord record : recentRecords) {
            if (records.size() >= safeLimit) {
                break;
            }
            records.add(record);
        }
        return records;
    }

    private ToolExecutionRecord finish(ToolExecutionRecord record,
                                       ToolExecutionStatus status,
                                       boolean success,
                                       String responseSummary,
                                       String errorType,
                                       String errorMessage) {
        if (record == null) {
            return null;
        }
        LocalDateTime endTime = LocalDateTime.now();
        record.setStatus(status);
        record.setSuccess(success);
        record.setResponseSummary(safeSummary(responseSummary));
        record.setErrorType(errorType);
        record.setErrorMessage(safeSummary(errorMessage));
        record.setEndTime(endTime);
        record.setCostMillis(record.getStartTime() == null ? 0L : Duration.between(record.getStartTime(), endTime).toMillis());
        appendRecent(record);
        appendCurrentTask(record);
        return record;
    }

    @SuppressWarnings("unchecked")
    private void appendCurrentTask(ToolExecutionRecord record) {
        ToolRuntimeScope scope = CURRENT_SCOPE.get();
        if (scope == null || scope.task == null) {
            return;
        }
        Map<String, Object> context = scope.task.getContext();
        if (context == null) {
            context = new LinkedHashMap<>();
            scope.task.setContext(context);
        }
        Object existing = context.get("toolRuntimeTrace");
        List<Map<String, Object>> traces;
        if (existing instanceof List<?> list) {
            traces = (List<Map<String, Object>>) list;
        } else {
            traces = new ArrayList<>();
            context.put("toolRuntimeTrace", traces);
        }
        traces.add(toMap(record));
        if (traces.size() > MAX_TASK_TOOL_TRACE) {
            traces.remove(0);
        }
    }

    private void appendRecent(ToolExecutionRecord record) {
        recentRecords.addFirst(record);
        while (recentRecords.size() > MAX_RECENT_RECORDS) {
            recentRecords.pollLast();
        }
    }

    private Map<String, Object> toMap(ToolExecutionRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("toolCallId", record.getToolCallId());
        map.put("taskId", record.getTaskId());
        map.put("traceId", record.getTraceId());
        map.put("executionId", record.getExecutionId());
        map.put("agentOrSkill", record.getAgentOrSkill());
        map.put("toolName", record.getToolName());
        map.put("logicalToolName", record.getLogicalToolName());
        map.put("category", record.getCategory());
        map.put("accessLevel", record.getAccessLevel() == null ? "" : record.getAccessLevel().name());
        map.put("sourceType", record.getSourceType() == null ? "" : record.getSourceType().name());
        map.put("status", record.getStatus() == null ? "" : record.getStatus().name());
        map.put("success", record.getSuccess());
        map.put("requestSummary", record.getRequestSummary());
        map.put("responseSummary", record.getResponseSummary());
        map.put("errorType", record.getErrorType());
        map.put("errorMessage", record.getErrorMessage());
        map.put("costMillis", record.getCostMillis());
        map.put("startTime", record.getStartTime() == null ? null : record.getStartTime().toString());
        map.put("endTime", record.getEndTime() == null ? null : record.getEndTime().toString());
        map.put("metadata", sanitizeMap(record.getMetadata()));
        return map;
    }

    private Map<String, Object> copy(Map<String, Object> metadata) {
        return metadata == null ? new LinkedHashMap<>() : sanitizeMap(metadata);
    }

    private String safeSummary(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value
                .replaceAll("(?i)(api[_-]?key|token|secret|password|authorization)\\s*[:=]\\s*\\S+", "$1=***")
                .replaceAll("sk-[A-Za-z0-9_\\-]{12,}", "sk-***");
        return sanitized.length() <= 1200 ? sanitized : sanitized.substring(0, 1200) + "...truncated...";
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((key, value) -> sanitized.put(String.valueOf(key), sanitizeValue(String.valueOf(key), value)));
        return sanitized;
    }

    private Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        String lowerKey = key == null ? "" : key.toLowerCase();
        if (lowerKey.contains("key") || lowerKey.contains("token") || lowerKey.contains("secret")
                || lowerKey.contains("password") || lowerKey.contains("authorization")) {
            return "***";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> nested.put(String.valueOf(nestedKey),
                    sanitizeValue(String.valueOf(nestedKey), nestedValue)));
            return nested;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> sanitizeValue(key, item)).toList();
        }
        if (value instanceof String text) {
            return safeSummary(text);
        }
        return value;
    }

    @lombok.Builder
    private static class ToolRuntimeScope {
        private EngineeringTaskEntity task;
        private String taskId;
        private String traceId;
        private String executionId;
        private String agentOrSkill;
    }

}
