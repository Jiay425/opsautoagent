package com.opsautoagent.domain.ops.service.dispatch;

import com.opsautoagent.domain.ops.adapter.repository.IOpsAlertRepository;
import com.opsautoagent.domain.codeops.service.CodeOpsAlertTriggerService;
import com.opsautoagent.domain.ops.agent.OpsAgentBootstrapService;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.entity.OpsDiagnosisDispatchEntity;
import com.opsautoagent.domain.ops.model.valobj.alert.OpsAlertDedupDecision;
import com.opsautoagent.domain.ops.service.alert.OpsAlertDedupService;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OpsDiagnosisDispatchService {

    @Resource
    private IOpsAlertRepository opsAlertRepository;

    @Resource
    private OpsAlertDedupService opsAlertDedupService;

    @Resource
    private OpsIncidentCommandFactory opsIncidentCommandFactory;

    @Resource
    private OpsDiagnosisJobExecutor opsDiagnosisJobExecutor;

    @Resource
    private OpsAgentBootstrapService opsAgentBootstrapService;

    @Resource
    private CodeOpsAlertTriggerService codeOpsAlertTriggerService;

    public boolean acceptAlertEvent(OpsAlertEventEntity alertEvent) {
        opsAlertRepository.saveAlertEvent(alertEvent);

        OpsAlertDedupDecision decision = opsAlertDedupService.evaluate(alertEvent);
        if (!decision.isAccepted()) {
            saveSkippedDispatch(alertEvent, decision);
            return false;
        }

        IncidentCommandEntity command = opsIncidentCommandFactory.createCommand(alertEvent);
        OpsDiagnosisDispatchEntity dispatch = OpsDiagnosisDispatchEntity.builder()
                .dispatchId("dispatch-" + UUID.randomUUID())
                .eventId(alertEvent.getEventId())
                .diagnosisId(command.getDiagnosisId())
                .serviceName(alertEvent.getServiceName())
                .dedupKey(decision.getDedupKey())
                .dispatchStatus("NEW")
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        opsAlertRepository.saveDiagnosisDispatch(dispatch);
        opsAgentBootstrapService.initialize(alertEvent, dispatch, command);
        opsDiagnosisJobExecutor.submit(alertEvent, dispatch, command);
        codeOpsAlertTriggerService.submitIncidentToFix(alertEvent, command);
        return true;
    }

    private void saveSkippedDispatch(OpsAlertEventEntity alertEvent, OpsAlertDedupDecision decision) {
        LocalDateTime now = LocalDateTime.now();
        opsAlertRepository.saveDiagnosisDispatch(OpsDiagnosisDispatchEntity.builder()
                .dispatchId("dispatch-" + UUID.randomUUID())
                .eventId(alertEvent.getEventId())
                .serviceName(alertEvent.getServiceName())
                .dedupKey(decision.getDedupKey())
                .dispatchStatus("SKIPPED")
                .skipReason(decision.getSkipReason())
                .createTime(now)
                .endTime(now)
                .updateTime(now)
                .build());
    }

}

