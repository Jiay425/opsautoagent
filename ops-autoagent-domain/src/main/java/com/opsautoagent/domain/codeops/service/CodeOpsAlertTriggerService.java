package com.opsautoagent.domain.codeops.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Service
public class CodeOpsAlertTriggerService {

    private final EngineeringTaskAgentService engineeringTaskAgentService;

    private final ThreadPoolExecutor threadPoolExecutor;

    @Value("${codeops.incident-to-fix.alert.enabled:true}")
    private boolean alertTriggerEnabled;

    public CodeOpsAlertTriggerService(EngineeringTaskAgentService engineeringTaskAgentService,
                                      ThreadPoolExecutor threadPoolExecutor) {
        this.engineeringTaskAgentService = engineeringTaskAgentService;
        this.threadPoolExecutor = threadPoolExecutor;
    }

    public void submitIncidentToFix(OpsAlertEventEntity alertEvent, IncidentCommandEntity command) {
        if (!alertTriggerEnabled) {
            return;
        }
        threadPoolExecutor.execute(() -> {
            try {
                EngineeringTaskEntity task = engineeringTaskAgentService.submit(EngineeringTaskEntity.builder()
                        .taskType("INCIDENT_TO_FIX")
                        .goal(buildGoal(alertEvent, command))
                        .repository(resolveRepository(alertEvent))
                        .focusAreas(List.of("incident", "code_location", "bug_fix", "test_verification", "release_risk"))
                        .context(buildContext(alertEvent, command))
                        .maxRounds(8)
                        .maxToolCalls(50)
                        .build());
                log.info("CodeOps Incident-to-Fix task submitted from alert. eventId={}, diagnosisId={}, taskId={}",
                        alertEvent.getEventId(), command.getDiagnosisId(), task.getTaskId());
            } catch (Exception e) {
                log.warn("Submit CodeOps Incident-to-Fix task failed. eventId={}, diagnosisId={}",
                        alertEvent.getEventId(), command.getDiagnosisId(), e);
            }
        });
    }

    private String buildGoal(OpsAlertEventEntity alertEvent, IncidentCommandEntity command) {
        return command.getServiceName() + " 触发线上告警 [" + alertEvent.getAlertRule() + "]，问题描述："
                + command.getProblem()
                + "。请完成 Incident-to-Fix：诊断线上证据，抽取异常类名/接口路径/可疑 Service，定位代码，生成修复补丁草稿、测试验证建议和发布风险观察项。";
    }

    private Map<String, Object> buildContext(OpsAlertEventEntity alertEvent, IncidentCommandEntity command) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "alertmanager");
        context.put("eventId", alertEvent.getEventId());
        context.put("alertRule", alertEvent.getAlertRule());
        context.put("severity", alertEvent.getSeverity());
        context.put("fingerprint", alertEvent.getFingerprint());
        context.put("serviceName", command.getServiceName());
        context.put("startTime", command.getStartTime());
        context.put("endTime", command.getEndTime());
        context.put("traceId", command.getTraceId());
        context.put("opsDiagnosisId", command.getDiagnosisId());
        context.put("repository", resolveRepository(alertEvent));
        context.put("allowPatchApply", booleanFlag(alertEvent, "codeops.allowPatchApply", true));
        context.put("allowTestPatchApply", booleanFlag(alertEvent, "codeops.allowTestPatchApply", true));
        context.put("alertmanagerPayload", alertEvent.getRawPayload());
        context.put("alertLabels", parseJson(alertEvent.getLabelsJson()));
        context.put("alertAnnotations", parseJson(alertEvent.getAnnotationsJson()));
        return context;
    }

    private String resolveRepository(OpsAlertEventEntity alertEvent) {
        Map<String, Object> labels = parseJson(alertEvent.getLabelsJson());
        Map<String, Object> annotations = parseJson(alertEvent.getAnnotationsJson());
        return firstNonBlank(
                stringValue(labels.get("repository")),
                stringValue(labels.get("repo")),
                stringValue(labels.get("code_repository")),
                stringValue(annotations.get("repository")),
                stringValue(annotations.get("repo")),
                stringValue(annotations.get("code_repository"))
        );
    }

    private boolean booleanFlag(OpsAlertEventEntity alertEvent, String key, boolean defaultValue) {
        Map<String, Object> labels = parseJson(alertEvent.getLabelsJson());
        Map<String, Object> annotations = parseJson(alertEvent.getAnnotationsJson());
        String value = firstNonBlank(stringValue(labels.get(key)), stringValue(annotations.get(key)));
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Map.of();
        }
        try {
            JSONObject object = JSON.parseObject(json);
            Map<String, Object> result = new LinkedHashMap<>();
            object.forEach(result::put);
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

}
