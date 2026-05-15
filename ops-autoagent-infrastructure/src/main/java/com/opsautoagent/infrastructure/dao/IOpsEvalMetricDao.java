package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsEvalMetric;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IOpsEvalMetricDao {

    int insert(OpsEvalMetric evalMetric);

}

