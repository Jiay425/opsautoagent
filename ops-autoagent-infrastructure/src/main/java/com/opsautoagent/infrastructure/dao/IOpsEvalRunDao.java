package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsEvalRun;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IOpsEvalRunDao {

    int insert(OpsEvalRun evalRun);

    int updateByRunId(OpsEvalRun evalRun);

}

