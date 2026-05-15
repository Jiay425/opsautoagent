package com.opsautoagent.infrastructure.adapter.repository;

import com.opsautoagent.domain.ops.adapter.repository.IOpsGovernanceRepository;
import com.opsautoagent.domain.ops.agent.governance.OpsToolPolicy;
import com.opsautoagent.domain.ops.model.entity.OpsAuditLogEntity;
import com.opsautoagent.domain.ops.model.entity.OpsToolCallLogEntity;
import com.opsautoagent.infrastructure.dao.IOpsAuditLogDao;
import com.opsautoagent.infrastructure.dao.IOpsToolCallLogDao;
import com.opsautoagent.infrastructure.dao.IOpsToolPolicyDao;
import com.opsautoagent.infrastructure.dao.po.OpsAuditLog;
import com.opsautoagent.infrastructure.dao.po.OpsToolCallLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;

@Slf4j
@Repository
public class OpsGovernanceRepository implements IOpsGovernanceRepository {

    @Resource
    private IOpsAuditLogDao opsAuditLogDao;

    @Resource
    private IOpsToolCallLogDao opsToolCallLogDao;

    @Resource
    private IOpsToolPolicyDao opsToolPolicyDao;

    @Override
    public void saveAuditLog(OpsAuditLogEntity auditLog) {
        OpsAuditLog po = OpsAuditLog.builder()
                .auditId(auditLog.getAuditId())
                .sessionId(auditLog.getSessionId())
                .diagnosisId(auditLog.getDiagnosisId())
                .operatorId(auditLog.getOperatorId())
                .clientIp(auditLog.getClientIp())
                .action(auditLog.getAction())
                .resource(auditLog.getResource())
                .requestJson(auditLog.getRequestJson())
                .result(auditLog.getResult())
                .reason(auditLog.getReason())
                .createTime(auditLog.getCreateTime())
                .build();
        opsAuditLogDao.insert(po);
    }

    @Override
    public void saveToolCallLog(OpsToolCallLogEntity toolCallLog) {
        OpsToolCallLog po = OpsToolCallLog.builder()
                .callId(toolCallLog.getCallId())
                .sessionId(toolCallLog.getSessionId())
                .diagnosisId(toolCallLog.getDiagnosisId())
                .toolName(toolCallLog.getToolName())
                .logicalToolName(toolCallLog.getLogicalToolName())
                .protocol(toolCallLog.getProtocol())
                .governanceDecision(toolCallLog.getGovernanceDecision())
                .target(toolCallLog.getTarget())
                .requestSummary(toolCallLog.getRequestSummary())
                .responseSummary(toolCallLog.getResponseSummary())
                .statusCode(toolCallLog.getStatusCode())
                .costMillis(toolCallLog.getCostMillis())
                .success(toolCallLog.getSuccess())
                .errorMessage(toolCallLog.getErrorMessage())
                .createTime(toolCallLog.getCreateTime())
                .build();
        opsToolCallLogDao.insert(po);
    }

    @Override
    public OpsToolPolicy queryToolPolicy(String toolName, String agentRole) {
        com.opsautoagent.infrastructure.dao.po.OpsToolPolicy po =
                opsToolPolicyDao.queryByToolNameAndAgentRole(toolName, agentRole);
        if (po == null) {
            return null;
        }
        return OpsToolPolicy.builder()
                .toolName(po.getToolName())
                .agentRole(po.getAgentRole())
                .enabled(toBoolean(po.getEnabled()))
                .maxCallsPerDiagnosis(po.getMaxCallsPerDiagnosis())
                .timeoutSeconds(po.getTimeoutSeconds())
                .requiredSeverity(po.getRequiredSeverity())
                .allowAutoExecute(toBoolean(po.getAllowAutoExecute()))
                .requiresApproval(toBoolean(po.getRequiresApproval()))
                .description(po.getDescription())
                .build();
    }

    private Boolean toBoolean(Integer value) {
        return value == null ? null : value == 1;
    }

}

