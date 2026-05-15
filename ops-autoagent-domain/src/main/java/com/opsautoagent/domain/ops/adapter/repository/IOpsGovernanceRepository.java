package com.opsautoagent.domain.ops.adapter.repository;

import com.opsautoagent.domain.ops.agent.governance.OpsToolPolicy;
import com.opsautoagent.domain.ops.model.entity.OpsAuditLogEntity;
import com.opsautoagent.domain.ops.model.entity.OpsToolCallLogEntity;

public interface IOpsGovernanceRepository {

    void saveAuditLog(OpsAuditLogEntity auditLog);

    void saveToolCallLog(OpsToolCallLogEntity toolCallLog);

    OpsToolPolicy queryToolPolicy(String toolName, String agentRole);

}

