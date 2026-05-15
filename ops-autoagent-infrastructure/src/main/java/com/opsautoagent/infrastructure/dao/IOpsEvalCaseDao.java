package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsEvalCase;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface IOpsEvalCaseDao {

    List<OpsEvalCase> queryEnabledCases();

    OpsEvalCase queryEnabledCaseByCaseId(String caseId);

}

