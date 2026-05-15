package com.opsautoagent.domain.ops.service.notify;

import com.opsautoagent.domain.ops.adapter.gateway.IOpsEmailGateway;
import com.opsautoagent.domain.ops.adapter.repository.IOpsNotificationRepository;
import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.entity.OpsNotificationRecordEntity;
import com.opsautoagent.domain.ops.model.entity.OpsServiceOwnerEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class OpsNotificationService {

    @Value("${ops.notify.enabled:true}")
    private boolean notifyEnabled;

    @Value("${ops.notify.email.enabled:true}")
    private boolean emailEnabled;

    @Resource
    private IOpsNotificationRepository opsNotificationRepository;

    @Resource
    private IOpsEmailGateway opsEmailGateway;

    @Resource
    private OpsServiceOwnerService opsServiceOwnerService;

    @Resource
    private OpsNotificationTemplateService opsNotificationTemplateService;

    public void notifyDiagnosis(OpsAlertEventEntity alertEvent,
                                IncidentCommandEntity command,
                                DiagnosisRecordEntity diagnosisRecord) {
        if (!notifyEnabled || !emailEnabled) {
            saveRecord(alertEvent, command, null, null, "SKIPPED", "notification is disabled");
            return;
        }

        OpsServiceOwnerEntity owner = opsServiceOwnerService.queryServiceOwner(alertEvent.getServiceName());
        if (owner == null) {
            saveRecord(alertEvent, command, null, null, "SKIPPED", "service owner is not configured");
            return;
        }
        if (Boolean.FALSE.equals(owner.getEnabled())) {
            saveRecord(alertEvent, command, null, null, "SKIPPED", "service owner is disabled");
            return;
        }

        String receiver = buildReceiver(owner);
        if (isBlank(receiver)) {
            saveRecord(alertEvent, command, null, null, "SKIPPED", "owner email is blank");
            return;
        }

        String subject = opsNotificationTemplateService.buildSubject(alertEvent);
        String content = opsNotificationTemplateService.buildContent(alertEvent, command, diagnosisRecord);
        try {
            opsEmailGateway.sendEmail(receiver, subject, content);
            saveRecord(alertEvent, command, receiver, subject, "SUCCESS", null);
        } catch (Exception e) {
            log.warn("Send ops notification failed. diagnosisId={}, serviceName={}",
                    command.getDiagnosisId(), alertEvent.getServiceName(), e);
            saveRecord(alertEvent, command, receiver, subject, "FAILED", summarizeException(e));
        }
    }

    private void saveRecord(OpsAlertEventEntity alertEvent,
                            IncidentCommandEntity command,
                            String receiver,
                            String subject,
                            String status,
                            String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        opsNotificationRepository.saveNotificationRecord(OpsNotificationRecordEntity.builder()
                .notificationId("notify-" + UUID.randomUUID())
                .diagnosisId(command == null ? null : command.getDiagnosisId())
                .serviceName(alertEvent == null ? "unknown-service" : alertEvent.getServiceName())
                .channel("EMAIL")
                .receiver(receiver)
                .severity(alertEvent == null ? null : alertEvent.getSeverity())
                .subject(subject)
                .sendStatus(status)
                .retryCount(0)
                .errorMessage(errorMessage)
                .sendTime(now)
                .createTime(now)
                .build());
    }

    private String buildReceiver(OpsServiceOwnerEntity owner) {
        Set<String> receivers = new LinkedHashSet<>();
        if (!isBlank(owner.getOwnerEmail())) {
            receivers.add(owner.getOwnerEmail().trim());
        }
        if (!isBlank(owner.getBackupOwnerEmail())) {
            receivers.add(owner.getBackupOwnerEmail().trim());
        }
        return String.join(",", receivers);
    }

    private String summarizeException(Exception e) {
        String message = e.getMessage();
        if (isBlank(message)) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

