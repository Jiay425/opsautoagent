package com.opsautoagent.trigger.http;

import com.opsautoagent.api.dto.CodeOpsIncidentFixViewDTO;
import com.opsautoagent.api.response.Response;
import com.opsautoagent.domain.codeops.agent.scheduler.IncidentScheduler;
import com.opsautoagent.domain.codeops.agent.security.CodeOpsSecurityGovernanceService;
import com.opsautoagent.domain.codeops.agent.security.HumanApprovalGate;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskStepEntity;
import com.opsautoagent.domain.codeops.service.EngineeringTaskAgentService;
import com.opsautoagent.domain.codeops.service.EngineeringTaskTraceService;
import com.opsautoagent.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/codeops/dashboard")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
public class CodeOpsDashboardController {

    @Resource
    private EngineeringTaskAgentService engineeringTaskAgentService;

    @Resource
    private EngineeringTaskTraceService engineeringTaskTraceService;

    @Resource
    private IncidentScheduler incidentScheduler;

    @Resource
    private CodeOpsSecurityGovernanceService codeOpsSecurityGovernanceService;

    @RequestMapping(value = "overview", method = RequestMethod.GET)
    public Response<Map<String, Object>> overview() {
        List<EngineeringTaskEntity> tasks = engineeringTaskAgentService.listRecent(20);
        Map<String, Object> scheduler = safeSchedulerStatus();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", LocalDateTime.now().toString());
        data.put("systemStatus", resolveSystemStatus(tasks, scheduler));
        data.put("services", serviceSummary(tasks, scheduler));
        data.put("taskSummary", taskSummary(tasks));
        data.put("scheduler", scheduler);
        data.put("security", codeOpsSecurityGovernanceService.globalSummary());
        return ok(data);
    }

    @RequestMapping(value = "alerts", method = RequestMethod.GET)
    public Response<List<Map<String, Object>>> alerts() {
        List<Map<String, Object>> alerts = new ArrayList<>();
        Map<String, Object> scheduler = safeSchedulerStatus();
        alerts.addAll(mapList(scheduler.get("activeIncidentItems")).stream()
                .map(item -> alertItem("AGGREGATING", item, null))
                .toList());
        alerts.addAll(mapList(scheduler.get("queuedIncidents")).stream()
                .map(item -> alertItem("QUEUED", item, null))
                .toList());
        engineeringTaskAgentService.listRecent(20).stream()
                .filter(task -> "INCIDENT_TO_FIX".equals(task.getTaskType()))
                .map(task -> alertItem(task.getStatus(), taskContextAsAlert(task), task))
                .forEach(alerts::add);
        return ok(alerts);
    }

    @RequestMapping(value = "tasks", method = RequestMethod.GET)
    public Response<List<Map<String, Object>>> tasks() {
        return ok(engineeringTaskAgentService.listRecent(20).stream()
                .map(this::taskRow)
                .toList());
    }

    @RequestMapping(value = "tasks/{taskId}", method = RequestMethod.GET)
    public Response<Map<String, Object>> taskDetail(@PathVariable("taskId") String taskId) {
        if (isBlank(taskId)) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("taskId cannot be blank")
                    .build();
        }
        EngineeringTaskEntity task = engineeringTaskAgentService.query(taskId);
        if (task == null) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("CodeOps task not found")
                    .build();
        }
        HumanApprovalGate.ApprovalRecord approval = engineeringTaskAgentService.approvalStatus(taskId);
        Map<String, Object> approvalMap = approval == null ? Map.of() : approval.toMap();
        CodeOpsIncidentFixViewDTO incidentView = engineeringTaskTraceService.buildIncidentFixView(task, approvalMap);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", taskRow(task));
        data.put("incidentView", incidentView);
        data.put("trace", engineeringTaskTraceService.buildTrace(task));
        data.put("security", codeOpsSecurityGovernanceService.taskSummary(task, approvalMap));
        data.put("failure", failureSummary(task, incidentView));
        return ok(data);
    }

    private Map<String, Object> safeSchedulerStatus() {
        try {
            return incidentScheduler.getStatus();
        } catch (Exception e) {
            return Map.of("running", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> taskSummary(List<EngineeringTaskEntity> tasks) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRecent", tasks.size());
        summary.put("running", countByStatus(tasks, "RUNNING"));
        summary.put("completed", countByStatus(tasks, "COMPLETED"));
        summary.put("failed", countByStatus(tasks, "FAILED"));
        summary.put("waitingApproval", countByStatus(tasks, "WAITING_APPROVAL"));
        summary.put("waitingBackground", countByStatus(tasks, "WAITING_BACKGROUND_TASK"));
        summary.put("incidentToFix", tasks.stream().filter(t -> "INCIDENT_TO_FIX".equals(t.getTaskType())).count());
        return summary;
    }

    private String resolveSystemStatus(List<EngineeringTaskEntity> tasks, Map<String, Object> scheduler) {
        if (countByStatus(tasks, "FAILED") > 0) {
            return "DEGRADED";
        }
        if (countByStatus(tasks, "RUNNING") > 0 || numberValue(scheduler.get("runningSlots")) > 0) {
            return "PROCESSING";
        }
        if (Boolean.FALSE.equals(scheduler.get("running"))) {
            return "SCHEDULER_DOWN";
        }
        return "READY";
    }

    private List<Map<String, Object>> serviceSummary(List<EngineeringTaskEntity> tasks, Map<String, Object> scheduler) {
        Map<String, Map<String, Object>> services = new LinkedHashMap<>();
        for (EngineeringTaskEntity task : tasks) {
            String service = serviceName(task);
            Map<String, Object> item = services.computeIfAbsent(service, this::emptyService);
            item.put("recentTasks", numberValue(item.get("recentTasks")) + 1);
            item.put("status", serviceStatus(String.valueOf(item.get("status")), task.getStatus()));
            item.put("lastTaskId", task.getTaskId());
            item.put("lastUpdate", task.getUpdateTime() == null ? "" : task.getUpdateTime().toString());
        }
        for (Map<String, Object> active : mapList(scheduler.get("activeIncidentItems"))) {
            String service = value(active.get("service"), "unknown-service");
            Map<String, Object> item = services.computeIfAbsent(service, this::emptyService);
            item.put("activeAlerts", numberValue(item.get("activeAlerts")) + numberValue(active.get("alertCount")));
            item.put("status", serviceStatus(String.valueOf(item.get("status")), "RUNNING"));
        }
        return new ArrayList<>(services.values());
    }

    private Map<String, Object> emptyService(String service) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("serviceName", service);
        item.put("status", "READY");
        item.put("recentTasks", 0);
        item.put("activeAlerts", 0);
        item.put("lastTaskId", "");
        item.put("lastUpdate", "");
        return item;
    }

    private String serviceStatus(String current, String taskStatus) {
        if ("FAILED".equals(taskStatus)) {
            return "DEGRADED";
        }
        if ("RUNNING".equals(taskStatus) || "WAITING_BACKGROUND_TASK".equals(taskStatus)) {
            return "PROCESSING";
        }
        if ("DEGRADED".equals(current) || "PROCESSING".equals(current)) {
            return current;
        }
        return "READY";
    }

    private Map<String, Object> taskRow(EngineeringTaskEntity task) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", task.getTaskId());
        row.put("taskType", task.getTaskType());
        row.put("serviceName", serviceName(task));
        row.put("alertName", value(contextValue(task, "alertName"), inferAlertName(task.getGoal())));
        row.put("endpoint", value(firstNonBlank(contextValue(task, "endpoint"), contextValue(task, "affectedEndpoints")), ""));
        row.put("severity", value(contextValue(task, "severity"), "UNKNOWN"));
        row.put("status", task.getStatus());
        row.put("stage", currentStage(task));
        row.put("progressPercent", progressPercent(task));
        row.put("usedToolCalls", task.getUsedToolCalls() == null ? 0 : task.getUsedToolCalls());
        row.put("stepCount", task.getSteps() == null ? 0 : task.getSteps().size());
        row.put("lastStepSummary", lastStepSummary(task));
        row.put("failureReason", failureSummary(task).get("reason"));
        row.put("requiresAttention", requiresAttention(task));
        row.put("createTime", task.getCreateTime() == null ? "" : task.getCreateTime().toString());
        row.put("updateTime", task.getUpdateTime() == null ? "" : task.getUpdateTime().toString());
        return row;
    }

    private Map<String, Object> alertItem(String status, Map<String, Object> source, EngineeringTaskEntity task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task == null ? value(source.get("groupKey"), value(source.get("taskId"), "")) : task.getTaskId());
        item.put("taskId", task == null ? value(source.get("taskId"), "") : task.getTaskId());
        item.put("serviceName", value(source.get("service"), task == null ? "unknown-service" : serviceName(task)));
        item.put("alertName", value(source.get("alertName"), task == null ? "unknown-alert" : inferAlertName(task.getGoal())));
        item.put("severity", value(source.get("severity"), "UNKNOWN"));
        item.put("status", status);
        item.put("summary", value(source.get("summary"), task == null ? "" : task.getGoal()));
        item.put("endpoint", value(source.get("endpoint"), value(source.get("endpoints"), "")));
        item.put("alertCount", numberValue(source.get("alertCount")));
        item.put("lastUpdate", value(source.get("lastUpdate"), task == null || task.getUpdateTime() == null ? "" : task.getUpdateTime().toString()));
        return item;
    }

    private Map<String, Object> taskContextAsAlert(EngineeringTaskEntity task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", task.getTaskId());
        item.put("service", serviceName(task));
        item.put("alertName", value(contextValue(task, "alertName"), inferAlertName(task.getGoal())));
        item.put("severity", value(contextValue(task, "severity"), "UNKNOWN"));
        item.put("summary", task.getGoal());
        item.put("endpoint", firstNonBlank(contextValue(task, "endpoint"), contextValue(task, "affectedEndpoints")));
        item.put("alertCount", numberValue(contextValue(task, "alertCount")));
        return item;
    }

    private Map<String, Object> failureSummary(EngineeringTaskEntity task) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("stage", "");
        failure.put("reason", "");
        failure.put("recoverable", false);
        if (task == null) {
            return failure;
        }
        if ("FAILED".equals(task.getStatus())) {
            EngineeringTaskStepEntity step = lastMeaningfulStep(task);
            failure.put("stage", step == null ? currentStage(task) : value(step.getSelectedSkill(), currentStage(task)));
            failure.put("reason", step == null ? value(task.getFinalSummary(), "Task failed") : value(step.getResultSummary(), "Task failed"));
            failure.put("recoverable", true);
        } else if ("WAITING_APPROVAL".equals(task.getStatus())) {
            failure.put("stage", "human_approval");
            failure.put("reason", "High-risk patch is waiting for human approval.");
            failure.put("recoverable", true);
        } else if ("WAITING_BACKGROUND_TASK".equals(task.getStatus())) {
            failure.put("stage", "test_verification");
            failure.put("reason", "Background verification task is still running.");
            failure.put("recoverable", true);
        }
        return failure;
    }

    private Map<String, Object> failureSummary(EngineeringTaskEntity task, CodeOpsIncidentFixViewDTO view) {
        Map<String, Object> failure = failureSummary(task);
        if (!isBlank(String.valueOf(failure.get("reason")))) {
            return failure;
        }
        if (view == null || view.getStages() == null) {
            return failure;
        }
        for (var stage : view.getStages()) {
            if (stage == null) {
                continue;
            }
            String status = stage.getStatus();
            if (!"FAILED".equals(status) && !"BLOCKED".equals(status)) {
                continue;
            }
            failure.put("stage", value(stage.getStageId(), value(stage.getStageName(), "")));
            failure.put("reason", value(stage.getSummary(), "Stage " + value(stage.getStageName(), stage.getStageId()) + " is " + status));
            failure.put("recoverable", true);
            return failure;
        }
        return failure;
    }

    private String currentStage(EngineeringTaskEntity task) {
        EngineeringTaskStepEntity step = lastMeaningfulStep(task);
        if (step == null || isBlank(step.getSelectedSkill())) {
            return "queued";
        }
        return switch (step.getSelectedSkill()) {
            case "ops_diagnosis" -> "ops_evidence";
            case "agent_loop_investigation", "repo_understanding" -> "code_localization";
            case "engineering_knowledge_rag" -> "knowledge_rag";
            case "bug_fix" -> "code_repair";
            case "test_verification" -> "test_verification";
            case "release_risk_analysis" -> "release_risk";
            default -> step.getSelectedSkill();
        };
    }

    private int progressPercent(EngineeringTaskEntity task) {
        int stepCount = task.getSteps() == null ? 0 : task.getSteps().size();
        if ("COMPLETED".equals(task.getStatus()) || "WAITING_APPROVAL".equals(task.getStatus())) {
            return 100;
        }
        if ("FAILED".equals(task.getStatus())) {
            return Math.min(95, Math.max(10, stepCount * 14));
        }
        return Math.min(95, Math.max(5, stepCount * 14));
    }

    private EngineeringTaskStepEntity lastMeaningfulStep(EngineeringTaskEntity task) {
        if (task == null || task.getSteps() == null || task.getSteps().isEmpty()) {
            return null;
        }
        for (int i = task.getSteps().size() - 1; i >= 0; i--) {
            EngineeringTaskStepEntity step = task.getSteps().get(i);
            if (step != null && !isBlank(step.getSelectedSkill())) {
                return step;
            }
        }
        return task.getSteps().get(task.getSteps().size() - 1);
    }

    private String lastStepSummary(EngineeringTaskEntity task) {
        EngineeringTaskStepEntity step = lastMeaningfulStep(task);
        return step == null ? value(task.getFinalSummary(), "") : value(step.getResultSummary(), "");
    }

    private boolean requiresAttention(EngineeringTaskEntity task) {
        return "FAILED".equals(task.getStatus())
                || "WAITING_APPROVAL".equals(task.getStatus())
                || "WAITING_BACKGROUND_TASK".equals(task.getStatus());
    }

    private long countByStatus(List<EngineeringTaskEntity> tasks, String status) {
        return tasks.stream().filter(task -> status.equals(task.getStatus())).count();
    }

    private String serviceName(EngineeringTaskEntity task) {
        return value(contextValue(task, "serviceName"), inferServiceName(task.getGoal()));
    }

    private Object contextValue(EngineeringTaskEntity task, String key) {
        return task == null || task.getContext() == null ? null : task.getContext().get(key);
    }

    private String inferServiceName(String goal) {
        if (isBlank(goal)) {
            return "unknown-service";
        }
        String[] parts = goal.trim().split("\\s+");
        return parts.length == 0 ? "unknown-service" : parts[0];
    }

    private String inferAlertName(String goal) {
        if (isBlank(goal)) {
            return "unknown-alert";
        }
        String normalized = goal.replace('[', ' ').replace(']', ' ');
        for (String part : normalized.split("\\s+")) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.contains("alert") || lower.contains("5xx") || lower.contains("latency")
                    || lower.contains("gc") || lower.contains("timeout")) {
                return part;
            }
        }
        return "incident";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> normalized.put(String.valueOf(key), mapValue));
            result.add(normalized);
        }
        return result;
    }

    private String firstNonBlank(Object first, Object second) {
        String a = first == null ? "" : String.valueOf(first);
        if (!a.isBlank()) {
            return a;
        }
        return second == null ? "" : String.valueOf(second);
    }

    private int numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String value(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private <T> Response<T> ok(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }
}
