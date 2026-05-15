package com.opsautoagent.domain.ops.service.execute;

import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAnalyzeEventEntity;
import com.opsautoagent.domain.common.tree.StrategyHandler;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class OpsStep1IncidentIntentNode extends AbstractOpsAgentExecuteSupport {

    @Resource
    private OpsStep2PrometheusMetricNode opsStep2PrometheusMetricNode;

    @Override
    protected String doApply(IncidentCommandEntity requestParameter,
                             DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        String content = String.format("Start diagnosing service [%s] in window [%s ~ %s]. Problem: %s",
                requestParameter.getServiceName(),
                requestParameter.getStartTime(),
                requestParameter.getEndTime(),
                requestParameter.getProblem());
        send(dynamicContext, OpsAnalyzeEventEntity.running("analysis", "intent", 1, content,
                requestParameter.getSessionId()));
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<IncidentCommandEntity, DefaultOpsAgentExecuteStrategyFactory.DynamicContext, String> get(
            IncidentCommandEntity requestParameter,
            DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return opsStep2PrometheusMetricNode;
    }

}


