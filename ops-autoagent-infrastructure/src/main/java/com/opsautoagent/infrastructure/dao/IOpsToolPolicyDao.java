package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsToolPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IOpsToolPolicyDao {

    OpsToolPolicy queryByToolNameAndAgentRole(@Param("toolName") String toolName,
                                              @Param("agentRole") String agentRole);

}

