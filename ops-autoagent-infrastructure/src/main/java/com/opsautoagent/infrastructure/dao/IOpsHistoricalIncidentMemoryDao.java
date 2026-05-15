package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsHistoricalIncidentMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IOpsHistoricalIncidentMemoryDao {

    int insert(OpsHistoricalIncidentMemory memory);

    List<OpsHistoricalIncidentMemory> querySimilar(@Param("serviceName") String serviceName,
                                                   @Param("keyword") String keyword,
                                                   @Param("limit") int limit);

}

