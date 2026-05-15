package com.opsautoagent.domain.ops.service.execute;

import com.opsautoagent.domain.ops.agent.governance.OpsToolGovernanceDecision;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.LogEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAnalyzeEventEntity;
import com.opsautoagent.domain.common.tree.StrategyHandler;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class OpsStep3ElkLogNode extends AbstractOpsAgentExecuteSupport {

    @Resource
    private OpsStep4SkyWalkingTraceNode opsStep4SkyWalkingTraceNode;

    @Override
    protected String doApply(IncidentCommandEntity requestParameter,
                             DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        if (!planExecutionService.shouldExecute(dynamicContext, "query_elasticsearch")) {
            LogEvidenceEntity skippedEvidence = LogEvidenceEntity.builder()
                    .source("elk")
                    .available(false)
                    .summary("Skipped by Planner Agent because query_elasticsearch is not required in this investigation plan.")
                    .errorSamples(java.util.List.of("SKIPPED: query_elasticsearch not selected by investigation plan"))
                    .rawData(null)
                    .build();
            dynamicContext.setLogEvidence(skippedEvidence);
            planExecutionService.recordDecision(requestParameter, dynamicContext, "query_elasticsearch", "Logs Agent",
                    "SKIPPED", "tool not selected by investigation plan");
            send(dynamicContext, OpsAnalyzeEventEntity.running("log", "elk", 3,
                    buildLogMessage(skippedEvidence), requestParameter.getSessionId()));
            return router(requestParameter, dynamicContext);
        }
        OpsToolGovernanceDecision governanceDecision = requestToolAccess(requestParameter, dynamicContext,
                "query_elasticsearch", "Logs Agent", "search error logs for root-cause investigation");
        if (!governanceDecision.isAllowed()) {
            LogEvidenceEntity deniedEvidence = LogEvidenceEntity.builder()
                    .source("elk")
                    .available(false)
                    .summary("Denied by Tool Governance: " + governanceDecision.getReason())
                    .errorSamples(java.util.List.of("DENIED: query_elasticsearch blocked by tool policy"))
                    .rawData(null)
                    .build();
            dynamicContext.setLogEvidence(deniedEvidence);
            send(dynamicContext, OpsAnalyzeEventEntity.running("log", "elk", 3,
                    buildLogMessage(deniedEvidence), requestParameter.getSessionId()));
            return router(requestParameter, dynamicContext);
        }
        LogEvidenceEntity logEvidence = logGateway.queryLogs(requestParameter);
        dynamicContext.setLogEvidence(logEvidence);
        planExecutionService.recordDecision(requestParameter, dynamicContext, "query_elasticsearch", "Logs Agent",
                "EXECUTED", "tool selected by investigation plan");
        send(dynamicContext, OpsAnalyzeEventEntity.running("log", "elk", 3,
                buildLogMessage(logEvidence), requestParameter.getSessionId()));
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<IncidentCommandEntity, DefaultOpsAgentExecuteStrategyFactory.DynamicContext, String> get(
            IncidentCommandEntity requestParameter,
            DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return opsStep4SkyWalkingTraceNode;
    }

}


