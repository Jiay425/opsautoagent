package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsIncidentState;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IOpsIncidentStateDao {

    int insert(OpsIncidentState incidentState);

    int updateByDiagnosisId(OpsIncidentState incidentState);

    OpsIncidentState queryByDiagnosisId(String diagnosisId);

}

