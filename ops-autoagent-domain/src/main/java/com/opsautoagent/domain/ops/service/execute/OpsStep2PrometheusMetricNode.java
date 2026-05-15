package com.opsautoagent.domain.ops.service.execute;

import com.opsautoagent.domain.ops.agent.governance.OpsToolGovernanceDecision;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.MetricEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAnalyzeEventEntity;
import com.opsautoagent.domain.common.tree.StrategyHandler;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class OpsStep2PrometheusMetricNode extends AbstractOpsAgentExecuteSupport {

    @Resource
    private OpsStep3ElkLogNode opsStep3ElkLogNode;

    @Override
    protected String doApply(IncidentCommandEntity requestParameter,
                             DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        if (!planExecutionService.shouldExecute(dynamicContext, "query_prometheus")) {
            MetricEvidenceEntity skippedEvidence = MetricEvidenceEntity.builder()
                    .source("prometheus")
                    .available(false)
                    .summary("Skipped by Planner Agent because query_prometheus is not required in this investigation plan.")
                    .observations(java.util.List.of("SKIPPED: query_prometheus not selected by investigation plan"))
                    .rawData(null)
                    .build();
            dynamicContext.setMetricEvidence(skippedEvidence);
            planExecutionService.recordDecision(requestParameter, dynamicContext, "query_prometheus", "Metrics Agent",
                    "SKIPPED", "tool not selected by investigation plan");
            send(dynamicContext, OpsAnalyzeEventEntity.running("metric", "prometheus", 2,
                    buildMetricMessage(skippedEvidence), requestParameter.getSessionId()));
            return router(requestParameter, dynamicContext);
        }
        OpsToolGovernanceDecision governanceDecision = requestToolAccess(requestParameter, dynamicContext,
                "query_prometheus", "Metrics Agent", "collect service metrics for root-cause investigation");
        if (!governanceDecision.isAllowed()) {
            MetricEvidenceEntity deniedEvidence = MetricEvidenceEntity.builder()
                    .source("prometheus")
                    .available(false)
                    .summary("Denied by Tool Governance: " + governanceDecision.getReason())
                    .observations(java.util.List.of("DENIED: query_prometheus blocked by tool policy"))
                    .rawData(null)
                    .build();
            dynamicContext.setMetricEvidence(deniedEvidence);
            send(dynamicContext, OpsAnalyzeEventEntity.running("metric", "prometheus", 2,
                    buildMetricMessage(deniedEvidence), requestParameter.getSessionId()));
            return router(requestParameter, dynamicContext);
        }
        MetricEvidenceEntity metricEvidence = metricGateway.queryMetrics(requestParameter);
        dynamicContext.setMetricEvidence(metricEvidence);
        planExecutionService.recordDecision(requestParameter, dynamicContext, "query_prometheus", "Metrics Agent",
                "EXECUTED", "tool selected by investigation plan");
        send(dynamicContext, OpsAnalyzeEventEntity.running("metric", "prometheus", 2,
                buildMetricMessage(metricEvidence), requestParameter.getSessionId()));
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<IncidentCommandEntity, DefaultOpsAgentExecuteStrategyFactory.DynamicContext, String> get(
            IncidentCommandEntity requestParameter,
            DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return opsStep3ElkLogNode;
    }

}


