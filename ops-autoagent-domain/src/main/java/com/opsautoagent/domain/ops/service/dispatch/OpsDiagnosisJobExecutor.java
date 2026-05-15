package com.opsautoagent.domain.ops.service.dispatch;

import com.opsautoagent.domain.ops.adapter.repository.IOpsAlertRepository;
import com.opsautoagent.domain.ops.adapter.repository.IOpsIncidentRepository;
import com.opsautoagent.domain.ops.agent.state.OpsAgentStateService;
import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.entity.OpsDiagnosisDispatchEntity;
import com.opsautoagent.domain.ops.service.OpsIncidentExecuteStrategy;
import com.opsautoagent.domain.ops.service.notify.OpsNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Service
public class OpsDiagnosisJobExecutor {

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Resource
    private IOpsAlertRepository opsAlertRepository;

    @Resource
    private IOpsIncidentRepository opsIncidentRepository;

    @Resource
    private OpsIncidentExecuteStrategy opsIncidentExecuteStrategy;

    @Resource
    private OpsNotificationService opsNotificationService;

    @Resource
    private OpsAgentStateService opsAgentStateService;

    public void submit(OpsAlertEventEntity alertEvent,
                       OpsDiagnosisDispatchEntity dispatch,
                       IncidentCommandEntity command) {
        threadPoolExecutor.execute(() -> runDiagnosis(alertEvent, dispatch, command));
    }

    private void runDiagnosis(OpsAlertEventEntity alertEvent,
                              OpsDiagnosisDispatchEntity dispatch,
                              IncidentCommandEntity command) {
        try {
            markRunning(dispatch);
            opsAgentStateService.markCollecting(command.getDiagnosisId());
            opsIncidentExecuteStrategy.execute(command, null);
            DiagnosisRecordEntity diagnosisRecord = opsIncidentRepository.queryDiagnosisRecord(command.getDiagnosisId());
            markCompleted(dispatch, "SUCCESS", null);
            opsAgentStateService.markSuccess(command.getDiagnosisId(), diagnosisRecord == null ? null : diagnosisRecord.getReport());
            opsNotificationService.notifyDiagnosis(alertEvent, command, diagnosisRecord);
        } catch (Exception e) {
            log.error("Auto alert diagnosis execution failed. dispatchId={}, diagnosisId={}",
                    dispatch.getDispatchId(), command.getDiagnosisId(), e);
            markCompleted(dispatch, "FAILED", summarizeException(e));
            opsAgentStateService.markFailed(command.getDiagnosisId(), summarizeException(e));
            DiagnosisRecordEntity diagnosisRecord = opsIncidentRepository.queryDiagnosisRecord(command.getDiagnosisId());
            opsNotificationService.notifyDiagnosis(alertEvent, command, diagnosisRecord);
        }
    }

    private void markRunning(OpsDiagnosisDispatchEntity dispatch) {
        LocalDateTime now = LocalDateTime.now();
        dispatch.setDispatchStatus("RUNNING");
        dispatch.setStartTime(now);
        dispatch.setUpdateTime(now);
        opsAlertRepository.updateDiagnosisDispatch(dispatch);
    }

    private void markCompleted(OpsDiagnosisDispatchEntity dispatch, String status, String reason) {
        LocalDateTime now = LocalDateTime.now();
        dispatch.setDispatchStatus(status);
        dispatch.setSkipReason(reason);
        dispatch.setEndTime(now);
        dispatch.setUpdateTime(now);
        opsAlertRepository.updateDiagnosisDispatch(dispatch);
    }

    private String summarizeException(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000) + "...";
    }

}

