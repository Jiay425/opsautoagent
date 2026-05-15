package com.opsautoagent.domain.ops.service.notify;

import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OpsNotificationTemplateService {

    @Value("${ops.notify.subject-prefix:[AutoAgent]}")
    private String subjectPrefix;

    @Value("${ops.notify.app-base-url:http://127.0.0.1:8099}")
    private String appBaseUrl;

    public String buildSubject(OpsAlertEventEntity alertEvent) {
        return subjectPrefix + " [" + safe(alertEvent.getSeverity(), "P2") + "] "
                + safe(alertEvent.getServiceName(), "unknown-service") + " 自动诊断结果";
    }

    public String buildContent(OpsAlertEventEntity alertEvent,
                               IncidentCommandEntity command,
                               DiagnosisRecordEntity diagnosisRecord) {
        StringBuilder builder = new StringBuilder();
        builder.append("服务名称: ").append(safe(alertEvent.getServiceName(), "unknown-service")).append("\n");
        builder.append("告警规则: ").append(safe(alertEvent.getAlertRule(), "UNKNOWN_ALERT")).append("\n");
        builder.append("严重级别: ").append(safe(alertEvent.getSeverity(), "P2")).append("\n");
        builder.append("诊断ID: ").append(safe(command.getDiagnosisId(), "-")).append("\n");
        builder.append("会话ID: ").append(safe(command.getSessionId(), "-")).append("\n");
        builder.append("时间窗口: ").append(safe(command.getStartTime(), "-"))
                .append(" ~ ").append(safe(command.getEndTime(), "-")).append("\n");
        builder.append("问题描述: ").append(safe(command.getProblem(), "-")).append("\n");

        if (diagnosisRecord != null) {
            builder.append("诊断状态: ").append(safe(diagnosisRecord.getStatus(), "UNKNOWN")).append("\n");
            if (!isBlank(diagnosisRecord.getErrorMessage())) {
                builder.append("失败原因: ").append(diagnosisRecord.getErrorMessage()).append("\n");
            }
            if (!isBlank(diagnosisRecord.getReport())) {
                builder.append("\n诊断报告摘要:\n");
                builder.append(abbreviate(diagnosisRecord.getReport(), 4000)).append("\n");
            }
        } else {
            builder.append("诊断状态: UNKNOWN\n");
            builder.append("说明: AutoAgent 已触发，但暂未查询到诊断落库记录。\n");
        }

        builder.append("\n诊断记录查询地址: ")
                .append(trimTrailingSlash(appBaseUrl))
                .append("/api/v1/ops/incident/record/")
                .append(safe(command.getDiagnosisId(), "-"))
                .append("\n");
        return builder.toString();
    }

    private String safe(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private String trimTrailingSlash(String value) {
        if (isBlank(value)) {
            return "http://127.0.0.1:8099";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

