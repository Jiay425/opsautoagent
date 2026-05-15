package com.opsautoagent.domain.ops.service.alert;

import com.opsautoagent.api.dto.OpsAlertWebhookAlertDTO;
import com.opsautoagent.api.dto.OpsAlertWebhookRequestDTO;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class OpsAlertNormalizeService {

    private static final DateTimeFormatter DEFAULT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MIN_VALID_YEAR = 2000;

    public List<OpsAlertEventEntity> normalizeAlertmanager(OpsAlertWebhookRequestDTO request) {
        List<OpsAlertEventEntity> results = new ArrayList<>();
        if (request == null || request.getAlerts() == null || request.getAlerts().isEmpty()) {
            return results;
        }

        LocalDateTime now = LocalDateTime.now();
        String rawPayload = JSON.toJSONString(request);
        for (OpsAlertWebhookAlertDTO alert : request.getAlerts()) {
            Map<String, String> labels = merge(request.getCommonLabels(), alert == null ? null : alert.getLabels());
            Map<String, String> annotations = merge(request.getCommonAnnotations(), alert == null ? null : alert.getAnnotations());

            results.add(OpsAlertEventEntity.builder()
                    .eventId("alert-" + UUID.randomUUID())
                    .source("alertmanager")
                    .serviceName(extractServiceName(labels, annotations))
                    .alertRule(firstNonBlank(labels.get("alertname"), labels.get("rule"), "unknown-rule"))
                    .severity(normalizeSeverity(firstNonBlank(labels.get("severity"), labels.get("level"), annotations.get("severity"))))
                    .status(normalizeStatus(firstNonBlank(alert == null ? null : alert.getStatus(), request.getStatus(), "firing")))
                    .fingerprint(alert == null ? null : alert.getFingerprint())
                    .traceId(firstNonBlank(labels.get("traceId"), labels.get("trace_id"), annotations.get("traceId"), annotations.get("trace_id")))
                    .startsAt(parseTime(alert == null ? null : alert.getStartsAt()))
                    .endsAt(parseTime(alert == null ? null : alert.getEndsAt()))
                    .labelsJson(JSON.toJSONString(labels))
                    .annotationsJson(JSON.toJSONString(annotations))
                    .rawPayload(rawPayload)
                    .receivedTime(now)
                    .createTime(now)
                    .build());
        }
        return results;
    }

    private Map<String, String> merge(Map<String, String> common, Map<String, String> current) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (common != null) {
            merged.putAll(common);
        }
        if (current != null) {
            merged.putAll(current);
        }
        return merged;
    }

    private String extractServiceName(Map<String, String> labels, Map<String, String> annotations) {
        String serviceName = firstNonBlank(
                labels.get("serviceName"),
                labels.get("service"),
                labels.get("application"),
                labels.get("app"),
                labels.get("job"),
                annotations.get("serviceName"),
                annotations.get("service"));
        return isBlank(serviceName) ? "unknown-service" : serviceName.trim();
    }

    private String normalizeSeverity(String severity) {
        if (isBlank(severity)) {
            return "P2";
        }
        String normalized = severity.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("P")) {
            return normalized;
        }
        return switch (normalized) {
            case "CRITICAL", "FATAL", "EMERGENCY" -> "P1";
            case "WARNING", "WARN", "ERROR", "HIGH" -> "P2";
            case "INFO", "NOTICE", "MEDIUM" -> "P3";
            default -> normalized;
        };
    }

    private String normalizeStatus(String status) {
        if (isBlank(status)) {
            return "firing";
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if ("resolved".equals(normalized)) {
            return "resolved";
        }
        return "firing";
    }

    private LocalDateTime parseTime(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            LocalDateTime parsed = OffsetDateTime.parse(value)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
            return isValidAlertTime(parsed) ? parsed : null;
        } catch (DateTimeParseException ignore) {
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(value, DEFAULT_TIME_FORMATTER);
            return isValidAlertTime(parsed) ? parsed : null;
        } catch (DateTimeParseException ignore) {
        }
        return null;
    }

    private boolean isValidAlertTime(LocalDateTime time) {
        return time != null && time.getYear() >= MIN_VALID_YEAR;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

