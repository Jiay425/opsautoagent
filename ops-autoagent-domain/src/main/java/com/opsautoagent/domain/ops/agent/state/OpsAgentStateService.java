package com.opsautoagent.domain.ops.agent.state;

import com.opsautoagent.domain.ops.adapter.repository.IOpsAgentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

@Service
public class OpsAgentStateService {

    @Value("${ops.agent.enabled:true}")
    private boolean agentEnabled;

    @Resource
    private IOpsAgentRepository opsAgentRepository;

    public void markCollecting(String diagnosisId) {
        updateStatus(diagnosisId, "COLLECTING", null, null);
    }

    public void markSuccess(String diagnosisId, String finalReport) {
        updateStatus(diagnosisId, "SUCCESS", finalReport, null);
    }

    public void markFailed(String diagnosisId, String errorMessage) {
        updateStatus(diagnosisId, "FAILED", null, errorMessage);
    }

    public void updateToolHistory(String diagnosisId, String toolHistoryJson) {
        if (!agentEnabled || isBlank(diagnosisId)) {
            return;
        }
        OpsIncidentState current = opsAgentRepository.queryIncidentStateByDiagnosisId(diagnosisId);
        if (current == null) {
            return;
        }
        opsAgentRepository.updateIncidentState(OpsIncidentState.builder()
                .diagnosisId(diagnosisId)
                .toolHistoryJson(toolHistoryJson)
                .updateTime(LocalDateTime.now())
                .build());
    }

    public void updateRunbookEvidence(String diagnosisId, String runbookEvidenceJson) {
        if (!agentEnabled || isBlank(diagnosisId)) {
            return;
        }
        OpsIncidentState current = opsAgentRepository.queryIncidentStateByDiagnosisId(diagnosisId);
        if (current == null) {
            return;
        }
        opsAgentRepository.updateIncidentState(OpsIncidentState.builder()
                .diagnosisId(diagnosisId)
                .runbookEvidenceJson(runbookEvidenceJson)
                .updateTime(LocalDateTime.now())
                .build());
    }

    public void updateReviewSnapshot(String diagnosisId,
                                     Integer currentRound,
                                     String metricsEvidenceJson,
                                     String logEvidenceJson,
                                     String traceEvidenceJson,
                                     String candidateRootCausesJson,
                                     String missingEvidenceJson,
                                     String reviewStatus,
                                     String status) {
        if (!agentEnabled || isBlank(diagnosisId)) {
            return;
        }
        OpsIncidentState current = opsAgentRepository.queryIncidentStateByDiagnosisId(diagnosisId);
        if (current == null) {
            return;
        }
        opsAgentRepository.updateIncidentState(OpsIncidentState.builder()
                .diagnosisId(diagnosisId)
                .currentRound(currentRound)
                .metricsEvidenceJson(metricsEvidenceJson)
                .logEvidenceJson(logEvidenceJson)
                .traceEvidenceJson(traceEvidenceJson)
                .candidateRootCausesJson(candidateRootCausesJson)
                .missingEvidenceJson(missingEvidenceJson)
                .reviewStatus(reviewStatus)
                .status(status)
                .updateTime(LocalDateTime.now())
                .build());
    }

    private void updateStatus(String diagnosisId, String status, String finalReport, String errorMessage) {
        if (!agentEnabled || isBlank(diagnosisId)) {
            return;
        }
        OpsIncidentState current = opsAgentRepository.queryIncidentStateByDiagnosisId(diagnosisId);
        if (current == null) {
            return;
        }
        opsAgentRepository.updateIncidentState(OpsIncidentState.builder()
                .diagnosisId(diagnosisId)
                .status(status)
                .finalReport(finalReport)
                .errorMessage(errorMessage)
                .updateTime(LocalDateTime.now())
                .build());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

