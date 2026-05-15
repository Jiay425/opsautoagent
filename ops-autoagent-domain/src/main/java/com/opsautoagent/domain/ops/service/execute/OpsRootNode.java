package com.opsautoagent.domain.ops.service.execute;

import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAnalyzeEventEntity;
import com.opsautoagent.domain.common.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
public class OpsRootNode extends AbstractOpsAgentExecuteSupport {

    @Resource
    private OpsStep1IncidentIntentNode opsStep1IncidentIntentNode;

    @Override
    protected String doApply(IncidentCommandEntity requestParameter,
                             DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        if (isBlank(requestParameter.getDiagnosisId())) {
            requestParameter.setDiagnosisId("diag-" + UUID.randomUUID());
        }
        dynamicContext.setStatus("RUNNING");

        log.info("Ops AutoAgent diagnosis started. diagnosisId={}, sessionId={}, serviceName={}",
                requestParameter.getDiagnosisId(), requestParameter.getSessionId(), requestParameter.getServiceName());

        try {
            return router(requestParameter, dynamicContext);
        } catch (Exception e) {
            log.error("Ops AutoAgent diagnosis failed. diagnosisId={}, sessionId={}",
                    requestParameter.getDiagnosisId(), requestParameter.getSessionId(), e);
            dynamicContext.setStatus("FAILED");
            dynamicContext.setErrorMessage(e.getMessage());
            saveDiagnosisRecord(requestParameter.getDiagnosisId(), requestParameter, dynamicContext, "FAILED", e.getMessage());
            send(dynamicContext, OpsAnalyzeEventEntity.error("Ops incident diagnosis failed: " + e.getMessage(),
                    requestParameter.getSessionId()));
            return "failed";
        }
    }

    @Override
    public StrategyHandler<IncidentCommandEntity, DefaultOpsAgentExecuteStrategyFactory.DynamicContext, String> get(
            IncidentCommandEntity requestParameter,
            DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return opsStep1IncidentIntentNode;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}


