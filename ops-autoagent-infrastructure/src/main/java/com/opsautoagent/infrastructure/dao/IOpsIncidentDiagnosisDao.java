package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsIncidentDiagnosis;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IOpsIncidentDiagnosisDao {

    int insert(OpsIncidentDiagnosis opsIncidentDiagnosis);

    OpsIncidentDiagnosis queryByDiagnosisId(String diagnosisId);

}

