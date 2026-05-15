package com.opsautoagent.domain.ops.adapter.repository;

import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;

public interface IOpsIncidentRepository {

    void saveDiagnosisRecord(DiagnosisRecordEntity diagnosisRecord);

    DiagnosisRecordEntity queryDiagnosisRecord(String diagnosisId);

}

