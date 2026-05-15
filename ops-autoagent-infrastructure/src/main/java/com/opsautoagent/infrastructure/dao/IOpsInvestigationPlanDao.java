package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsInvestigationPlan;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IOpsInvestigationPlanDao {

    int insert(OpsInvestigationPlan investigationPlan);

    OpsInvestigationPlan queryLatestByDiagnosisId(String diagnosisId);

}

