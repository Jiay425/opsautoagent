package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsDiagnosisDispatch;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IOpsDiagnosisDispatchDao {

    int insert(OpsDiagnosisDispatch dispatch);

    int updateByDispatchId(OpsDiagnosisDispatch dispatch);

    OpsDiagnosisDispatch queryLatestByDedupKey(String dedupKey);

    OpsDiagnosisDispatch queryRunningByServiceName(String serviceName);

}

