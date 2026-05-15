package com.opsautoagent.domain.ops.service.alert;

import com.opsautoagent.domain.ops.adapter.repository.IOpsAlertRepository;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.entity.OpsDiagnosisDispatchEntity;
import com.opsautoagent.domain.ops.model.valobj.alert.OpsAlertDedupDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class OpsAlertDedupService {

    @Value("${ops.alert.dedup-window-minutes:5}")
    private int dedupWindowMinutes;

    @Resource
    private IOpsAlertRepository opsAlertRepository;

    public OpsAlertDedupDecision evaluate(OpsAlertEventEntity alertEvent) {
        String dedupKey = buildDedupKey(alertEvent);
        if (alertEvent == null) {
            return OpsAlertDedupDecision.skipped(dedupKey, "alert event is null");
        }
        if (isBlank(alertEvent.getServiceName()) || "unknown-service".equalsIgnoreCase(alertEvent.getServiceName())) {
            return OpsAlertDedupDecision.skipped(dedupKey, "serviceName is missing");
        }
        if (!"firing".equalsIgnoreCase(alertEvent.getStatus())) {
            return OpsAlertDedupDecision.skipped(dedupKey, "alert status is not firing");
        }

        OpsDiagnosisDispatchEntity runningDispatch = opsAlertRepository.queryRunningDispatchByServiceName(alertEvent.getServiceName());
        if (runningDispatch != null) {
            return OpsAlertDedupDecision.skipped(dedupKey, "service already has running diagnosis");
        }

        OpsDiagnosisDispatchEntity latestDispatch = opsAlertRepository.queryLatestDispatchByDedupKey(dedupKey);
        if (latestDispatch != null && latestDispatch.getCreateTime() != null) {
            LocalDateTime boundary = LocalDateTime.now().minusMinutes(Math.max(1, dedupWindowMinutes));
            if (latestDispatch.getCreateTime().isAfter(boundary)) {
                return OpsAlertDedupDecision.skipped(dedupKey,
                        "duplicated alert within " + Math.max(1, dedupWindowMinutes) + " minutes");
            }
        }

        return OpsAlertDedupDecision.accepted(dedupKey);
    }

    public String buildDedupKey(OpsAlertEventEntity alertEvent) {
        if (alertEvent == null) {
            return "unknown-service|unknown-rule||";
        }
        return String.join("|",
                        safe(alertEvent.getServiceName()),
                        safe(alertEvent.getAlertRule()),
                        safe(alertEvent.getFingerprint()),
                        safe(alertEvent.getSeverity()))
                .toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

