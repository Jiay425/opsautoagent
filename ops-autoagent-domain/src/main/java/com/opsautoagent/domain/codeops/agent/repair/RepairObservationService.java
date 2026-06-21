package com.opsautoagent.domain.codeops.agent.repair;

import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolRequest;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolResult;
import com.opsautoagent.domain.codeops.agent.tool.ToolPermissionDecision;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.PatchAttemptEntity;
import com.opsautoagent.domain.codeops.model.entity.RepairObservationEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RepairObservationService {

    public static final String REPAIR_OBSERVATIONS_KEY = "repairObservations";
    public static final String PATCH_ATTEMPTS_KEY = "patchAttempts";

    public RepairObservationEntity recordToolObservation(EngineeringTaskEntity task,
                                                         String phase,
                                                         EngineeringToolRequest request,
                                                         ToolPermissionDecision permissionDecision,
                                                         EngineeringToolResult result) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("arguments", request == null || request.getArguments() == null ? Map.of() : request.getArguments());
        input.put("permission", permissionDecision == null ? Map.of() : Map.of(
                "status", permissionDecision.getStatus() == null ? "" : permissionDecision.getStatus(),
                "allowed", permissionDecision.isAllowed(),
                "requiresApproval", permissionDecision.isRequiresApproval(),
                "reason", permissionDecision.getReason() == null ? "" : permissionDecision.getReason(),
                "policy", permissionDecision.getPolicy() == null ? Map.of() : permissionDecision.getPolicy()
        ));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", result == null ? "" : result.getOutput());
        output.put("metadata", result == null || result.getMetadata() == null ? Map.of() : result.getMetadata());
        return record(task, RepairObservationEntity.builder()
                .taskId(task == null ? "" : task.getTaskId())
                .phase(blankToDefault(phase, "TOOL"))
                .source("tool")
                .action(request == null ? "" : request.getToolName())
                .status(result == null ? "FAILED" : result.getStatus())
                .success(result != null && result.isSuccess())
                .summary(result == null ? "" : result.getSummary())
                .errorType(result == null ? "" : result.getErrorType())
                .errorMessage(result == null ? "" : result.getErrorMessage())
                .input(input)
                .output(output)
                .build());
    }

    public RepairObservationEntity record(EngineeringTaskEntity task, RepairObservationEntity observation) {
        if (task == null || observation == null) {
            return observation;
        }
        RepairObservationEntity completed = observation;
        completed.setObservationId(completed.getObservationId() == null || completed.getObservationId().isBlank()
                ? "obs-" + UUID.randomUUID() : completed.getObservationId());
        completed.setTaskId(completed.getTaskId() == null || completed.getTaskId().isBlank()
                ? task.getTaskId() : completed.getTaskId());
        completed.setCreateTime(completed.getCreateTime() == null ? LocalDateTime.now() : completed.getCreateTime());
        synchronized (task) {
            Map<String, Object> context = mutableContext(task);
            List<Map<String, Object>> observations = new ArrayList<>(rawList(context.get(REPAIR_OBSERVATIONS_KEY)));
            observations.add(completed.toRawOutput());
            context.put(REPAIR_OBSERVATIONS_KEY, observations);
            context.put("latestRepairObservation", completed.toRawOutput());
            task.setContext(context);
        }
        return completed;
    }

    public PatchAttemptEntity recordPatchAttempt(EngineeringTaskEntity task, PatchAttemptEntity attempt) {
        if (task == null || attempt == null) {
            return attempt;
        }
        PatchAttemptEntity completed = attempt;
        completed.setAttemptNo(completed.getAttemptNo() == null || completed.getAttemptNo() <= 0
                ? nextAttemptNo(task) : completed.getAttemptNo());
        completed.setCreateTime(completed.getCreateTime() == null ? LocalDateTime.now() : completed.getCreateTime());
        synchronized (task) {
            Map<String, Object> context = mutableContext(task);
            List<Map<String, Object>> attempts = new ArrayList<>(rawList(context.get(PATCH_ATTEMPTS_KEY)));
            attempts.add(completed.toRawOutput());
            context.put(PATCH_ATTEMPTS_KEY, attempts);
            context.put("latestPatchAttempt", completed.toRawOutput());
            task.setContext(context);
        }
        return completed;
    }

    public void updateLatestPatchAttempt(EngineeringTaskEntity task,
                                         Map<String, Object> testResult,
                                         Map<String, Object> nextFailureDiagnostic,
                                         Boolean recovered) {
        if (task == null) {
            return;
        }
        synchronized (task) {
            Map<String, Object> context = mutableContext(task);
            List<Map<String, Object>> attempts = new ArrayList<>(rawList(context.get(PATCH_ATTEMPTS_KEY)));
            if (attempts.isEmpty()) {
                return;
            }
            Map<String, Object> latest = new LinkedHashMap<>(attempts.get(attempts.size() - 1));
            if (testResult != null && !testResult.isEmpty()) {
                latest.put("testResult", testResult);
            }
            if (nextFailureDiagnostic != null && !nextFailureDiagnostic.isEmpty()) {
                latest.put("nextFailureDiagnostic", nextFailureDiagnostic);
            }
            if (recovered != null) {
                latest.put("recovered", recovered);
            }
            attempts.set(attempts.size() - 1, latest);
            context.put(PATCH_ATTEMPTS_KEY, attempts);
            context.put("latestPatchAttempt", latest);
            task.setContext(context);
        }
    }

    private int nextAttemptNo(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) {
            return 1;
        }
        return rawList(task.getContext().get(PATCH_ATTEMPTS_KEY)).size() + 1;
    }

    private List<Map<String, Object>> rawList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> raw = new LinkedHashMap<>();
                map.forEach((key, rawValue) -> raw.put(String.valueOf(key), rawValue));
                result.add(raw);
            }
        }
        return result;
    }

    private Map<String, Object> mutableContext(EngineeringTaskEntity task) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (task.getContext() != null) {
            context.putAll(task.getContext());
        }
        return context;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
