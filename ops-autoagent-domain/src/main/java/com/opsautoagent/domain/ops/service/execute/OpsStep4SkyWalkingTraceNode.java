package com.opsautoagent.domain.ops.service.execute;

import com.opsautoagent.domain.ops.agent.governance.OpsToolGovernanceDecision;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAnalyzeEventEntity;
import com.opsautoagent.domain.ops.model.entity.TraceEvidenceEntity;
import com.opsautoagent.domain.common.tree.StrategyHandler;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class OpsStep4SkyWalkingTraceNode extends AbstractOpsAgentExecuteSupport {

    @Resource
    private OpsStep5EvidenceCorrelationNode opsStep5EvidenceCorrelationNode;

    @Override
    protected String doApply(IncidentCommandEntity requestParameter,
                             DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        if (!planExecutionService.shouldExecute(dynamicContext, "query_skywalking_trace")) {
            TraceEvidenceEntity skippedEvidence = TraceEvidenceEntity.builder()
                    .source("skywalking")
                    .available(false)
                    .summary("Skipped by Planner Agent because query_skywalking_trace is not required in this investigation plan.")
                    .spans(java.util.List.of("SKIPPED: query_skywalking_trace not selected by investigation plan"))
                    .rawData(null)
                    .build();
            dynamicContext.setTraceEvidence(skippedEvidence);
            planExecutionService.recordDecision(requestParameter, dynamicContext, "query_skywalking_trace", "Trace Agent",
                    "SKIPPED", "tool not selected by investigation plan");
            send(dynamicContext, OpsAnalyzeEventEntity.running("trace", "skywalking", 4,
                    buildTraceMessage(skippedEvidence), requestParameter.getSessionId()));
            return router(requestParameter, dynamicContext);
        }
        OpsToolGovernanceDecision governanceDecision = requestToolAccess(requestParameter, dynamicContext,
                "query_skywalking_trace", "Trace Agent", "collect slow or error traces for root-cause investigation");
        if (!governanceDecision.isAllowed()) {
            TraceEvidenceEntity deniedEvidence = TraceEvidenceEntity.builder()
                    .source("skywalking")
                    .available(false)
                    .summary("Denied by Tool Governance: " + governanceDecision.getReason())
                    .spans(java.util.List.of("DENIED: query_skywalking_trace blocked by tool policy"))
                    .rawData(null)
                    .build();
            dynamicContext.setTraceEvidence(deniedEvidence);
            send(dynamicContext, OpsAnalyzeEventEntity.running("trace", "skywalking", 4,
                    buildTraceMessage(deniedEvidence), requestParameter.getSessionId()));
            return router(requestParameter, dynamicContext);
        }
        TraceEvidenceEntity traceEvidence = traceGateway.queryTrace(requestParameter);
        dynamicContext.setTraceEvidence(traceEvidence);
        planExecutionService.recordDecision(requestParameter, dynamicContext, "query_skywalking_trace", "Trace Agent",
                "EXECUTED", "tool selected by investigation plan");
        send(dynamicContext, OpsAnalyzeEventEntity.running("trace", "skywalking", 4,
                buildTraceMessage(traceEvidence), requestParameter.getSessionId()));
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<IncidentCommandEntity, DefaultOpsAgentExecuteStrategyFactory.DynamicContext, String> get(
            IncidentCommandEntity requestParameter,
            DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return opsStep5EvidenceCorrelationNode;
    }

}


