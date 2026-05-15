package com.opsautoagent.infrastructure.adapter.repository;

import com.opsautoagent.domain.ops.adapter.repository.IOpsIncidentRepository;
import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;
import com.opsautoagent.infrastructure.dao.IOpsIncidentDiagnosisDao;
import com.opsautoagent.infrastructure.dao.po.OpsIncidentDiagnosis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;

@Slf4j
@Repository
public class OpsIncidentRepository implements IOpsIncidentRepository {

    @Resource
    private IOpsIncidentDiagnosisDao opsIncidentDiagnosisDao;

    @Override
    public void saveDiagnosisRecord(DiagnosisRecordEntity diagnosisRecord) {
        OpsIncidentDiagnosis po = OpsIncidentDiagnosis.builder()
                .diagnosisId(diagnosisRecord.getDiagnosisId())
                .sessionId(diagnosisRecord.getSessionId())
                .serviceName(diagnosisRecord.getServiceName())
                .startTime(diagnosisRecord.getStartTime())
                .endTime(diagnosisRecord.getEndTime())
                .problem(diagnosisRecord.getProblem())
                .traceId(diagnosisRecord.getTraceId())
                .status(diagnosisRecord.getStatus())
                .requestJson(diagnosisRecord.getRequestJson())
                .metricEvidenceJson(diagnosisRecord.getMetricEvidenceJson())
                .logEvidenceJson(diagnosisRecord.getLogEvidenceJson())
                .traceEvidenceJson(diagnosisRecord.getTraceEvidenceJson())
                .evidenceChainJson(diagnosisRecord.getEvidenceChainJson())
                .runbookJson(diagnosisRecord.getRunbookJson())
                .report(diagnosisRecord.getReport())
                .errorMessage(diagnosisRecord.getErrorMessage())
                .createTime(diagnosisRecord.getCreateTime())
                .updateTime(diagnosisRecord.getUpdateTime())
                .build();

        opsIncidentDiagnosisDao.insert(po);
        log.info("Saved ops incident diagnosis record. diagnosisId={}, sessionId={}",
                diagnosisRecord.getDiagnosisId(), diagnosisRecord.getSessionId());
    }

    @Override
    public DiagnosisRecordEntity queryDiagnosisRecord(String diagnosisId) {
        OpsIncidentDiagnosis po = opsIncidentDiagnosisDao.queryByDiagnosisId(diagnosisId);
        if (po == null) {
            return null;
        }
        return DiagnosisRecordEntity.builder()
                .diagnosisId(po.getDiagnosisId())
                .sessionId(po.getSessionId())
                .serviceName(po.getServiceName())
                .startTime(po.getStartTime())
                .endTime(po.getEndTime())
                .problem(po.getProblem())
                .traceId(po.getTraceId())
                .status(po.getStatus())
                .requestJson(po.getRequestJson())
                .metricEvidenceJson(po.getMetricEvidenceJson())
                .logEvidenceJson(po.getLogEvidenceJson())
                .traceEvidenceJson(po.getTraceEvidenceJson())
                .evidenceChainJson(po.getEvidenceChainJson())
                .runbookJson(po.getRunbookJson())
                .report(po.getReport())
                .errorMessage(po.getErrorMessage())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }

}

