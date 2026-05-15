package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IOpsAuditLogDao {

    int insert(OpsAuditLog opsAuditLog);

}

