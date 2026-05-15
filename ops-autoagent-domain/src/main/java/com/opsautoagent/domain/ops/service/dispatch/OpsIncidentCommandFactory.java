package com.opsautoagent.domain.ops.service.dispatch;

import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class OpsIncidentCommandFactory {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${ops.alert.max-step:9}")
    private int maxStep;

    public IncidentCommandEntity createCommand(OpsAlertEventEntity alertEvent) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime incidentStart = alertEvent.getStartsAt() != null ? alertEvent.getStartsAt() : now.minusMinutes(10);
        LocalDateTime startTime = incidentStart.minusMinutes(10);
        LocalDateTime endTime = resolveEndTime(alertEvent, startTime, now);

        return IncidentCommandEntity.builder()
                .serviceName(alertEvent.getServiceName())
                .startTime(startTime.format(DATE_TIME_FORMATTER))
                .endTime(endTime.format(DATE_TIME_FORMATTER))
                .problem(buildProblem(alertEvent))
                .traceId(alertEvent.getTraceId())
                .maxStep(Math.max(1, maxStep))
                .sessionId(UUID.randomUUID().toString())
                .diagnosisId("diag-" + UUID.randomUUID())
                .build();
    }

    private LocalDateTime resolveEndTime(OpsAlertEventEntity alertEvent, LocalDateTime startTime, LocalDateTime now) {
        LocalDateTime endTime = alertEvent.getEndsAt();
        if (endTime == null || endTime.isBefore(startTime)) {
            return now;
        }
        return endTime;
    }

    private String buildProblem(OpsAlertEventEntity alertEvent) {
        String serviceName = blankToDefault(alertEvent.getServiceName(), "unknown-service");
        String alertRule = blankToDefault(alertEvent.getAlertRule(), "UNKNOWN_ALERT");
        String severity = blankToDefault(alertEvent.getSeverity(), "P2");
        return serviceName + " 在最近 10 分钟触发告警 [" + alertRule + "]，严重级别 " + severity
                + "。请分析 Prometheus 指标、ELK 日志、SkyWalking 链路与运维 Runbook，判断根因候选，并给出临时止血和长期优化建议。";
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

}

