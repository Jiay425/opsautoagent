package com.opsautoagent.domain.ops.service;

import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.agent.memory.OpsIncidentWorkingMemoryService;
import com.opsautoagent.domain.ops.agent.plan.OpsAgentPlanExecutionService;
import com.opsautoagent.domain.ops.service.execute.DefaultOpsAgentExecuteStrategyFactory;
import com.opsautoagent.domain.common.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Ops incident diagnosis entry. The real workflow is driven by Ops AutoAgent step nodes.
 */
@Slf4j
@Service
public class OpsIncidentExecuteStrategy {

    private final DefaultOpsAgentExecuteStrategyFactory opsAgentExecuteStrategyFactory;

    private final OpsAgentPlanExecutionService opsAgentPlanExecutionService;

    private final OpsIncidentWorkingMemoryService workingMemoryService;

    public OpsIncidentExecuteStrategy(DefaultOpsAgentExecuteStrategyFactory opsAgentExecuteStrategyFactory,
                                      OpsAgentPlanExecutionService opsAgentPlanExecutionService,
                                      OpsIncidentWorkingMemoryService workingMemoryService) {
        this.opsAgentExecuteStrategyFactory = opsAgentExecuteStrategyFactory;
        this.opsAgentPlanExecutionService = opsAgentPlanExecutionService;
        this.workingMemoryService = workingMemoryService;
    }

    public void execute(IncidentCommandEntity command, ResponseBodyEmitter emitter) {
        StrategyHandler<IncidentCommandEntity, DefaultOpsAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                = opsAgentExecuteStrategyFactory.opsAgentStrategyHandler();
        DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext =
                DefaultOpsAgentExecuteStrategyFactory.DynamicContext.builder()
                        .emitter(emitter)
                        .build();
        opsAgentPlanExecutionService.loadPlan(command, dynamicContext);
        workingMemoryService.initialize(command, dynamicContext);
        try {
            String result = executeHandler.apply(command, dynamicContext);
            log.info("Ops AutoAgent diagnosis finished. diagnosisId={}, sessionId={}, result={}",
                    command.getDiagnosisId(), command.getSessionId(), result);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(e);
        }
    }

}


