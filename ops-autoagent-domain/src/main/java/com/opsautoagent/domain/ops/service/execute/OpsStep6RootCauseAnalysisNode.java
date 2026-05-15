package com.opsautoagent.domain.ops.service.execute;

import com.opsautoagent.domain.ops.agent.governance.OpsToolGovernanceDecision;
import com.opsautoagent.domain.ops.agent.skill.OpsAgentSkill;
import com.opsautoagent.domain.ops.agent.skill.OpsAgentSkillService;
import com.opsautoagent.domain.ops.agent.state.OpsAgentStateService;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAnalyzeEventEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import com.opsautoagent.domain.common.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;

@Service
public class OpsStep6RootCauseAnalysisNode extends AbstractOpsAgentExecuteSupport {

    @Resource
    private OpsStep7ReportGenerationNode opsStep7ReportGenerationNode;

    @Resource
    private OpsAgentSkillService opsAgentSkillService;

    @Resource
    private OpsAgentStateService opsAgentStateService;

    @Override
    protected String doApply(IncidentCommandEntity requestParameter,
                             DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        if (dynamicContext.getRunbookMatches() != null && !dynamicContext.getRunbookMatches().isEmpty()) {
            send(dynamicContext, OpsAnalyzeEventEntity.running("rag", "runbook", 6,
                    "Runbook patterns were already retrieved before Evidence Reviewer. Reusing matches: "
                            + dynamicContext.getRunbookMatches().size(),
                    requestParameter.getSessionId()));
            return router(requestParameter, dynamicContext);
        }
        if (!planExecutionService.shouldExecute(dynamicContext, "query_runbook")) {
            List<RunbookMatchEntity> runbookMatches = matchLocalSkills(requestParameter, dynamicContext);
            dynamicContext.setRunbookMatches(runbookMatches);
            opsAgentStateService.updateRunbookEvidence(requestParameter.getDiagnosisId(), JSON.toJSONString(runbookMatches));
            planExecutionService.recordDecision(requestParameter, dynamicContext, "query_runbook", "Runbook Agent",
                    "SKIPPED", "tool not selected by investigation plan");
            send(dynamicContext, OpsAnalyzeEventEntity.running("rag", "runbook", 6,
                    "Skipped external runbook search by Planner Agent. Local ops skills matched: " + runbookMatches.size(),
                    requestParameter.getSessionId()));
            return router(requestParameter, dynamicContext);
        }
        OpsToolGovernanceDecision governanceDecision = requestToolAccess(requestParameter, dynamicContext,
                "query_runbook", "Runbook Agent", "retrieve runbook context for remediation suggestions");
        if (!governanceDecision.isAllowed()) {
            List<RunbookMatchEntity> runbookMatches = matchLocalSkills(requestParameter, dynamicContext);
            dynamicContext.setRunbookMatches(runbookMatches);
            opsAgentStateService.updateRunbookEvidence(requestParameter.getDiagnosisId(), JSON.toJSONString(runbookMatches));
            send(dynamicContext, OpsAnalyzeEventEntity.running("rag", "runbook", 6,
                    "Denied external runbook search by Tool Governance: " + governanceDecision.getReason()
                            + ". Local ops skills matched: " + runbookMatches.size(),
                    requestParameter.getSessionId()));
            return router(requestParameter, dynamicContext);
        }
        List<RunbookMatchEntity> runbookMatches = runbookGateway.search(
                requestParameter, dynamicContext.getRootCauseCandidates(), 4);
        List<RunbookMatchEntity> skillMatches = matchLocalSkills(requestParameter, dynamicContext);
        List<RunbookMatchEntity> combinedMatches = mergeRunbookMatches(runbookMatches, skillMatches);
        dynamicContext.setRunbookMatches(combinedMatches);
        opsAgentStateService.updateRunbookEvidence(requestParameter.getDiagnosisId(), JSON.toJSONString(combinedMatches));
        planExecutionService.recordDecision(requestParameter, dynamicContext, "query_runbook", "Runbook Agent",
                "EXECUTED", "tool selected by investigation plan");
        send(dynamicContext, OpsAnalyzeEventEntity.running("rag", "runbook", 6,
                formatRunbookMatches(combinedMatches), requestParameter.getSessionId()));
        send(dynamicContext, OpsAnalyzeEventEntity.running("skill", "runbook_skill", 6,
                formatRunbookMatches(skillMatches), requestParameter.getSessionId()));
        send(dynamicContext, OpsAnalyzeEventEntity.running("analysis", "root_cause", 6,
                "Evidence chain, Runbook context, and structured ops skills have been generated. Ranking root-cause candidates and preparing remediation suggestions.",
                requestParameter.getSessionId()));
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<IncidentCommandEntity, DefaultOpsAgentExecuteStrategyFactory.DynamicContext, String> get(
            IncidentCommandEntity requestParameter,
            DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return opsStep7ReportGenerationNode;
    }

    private List<RunbookMatchEntity> matchLocalSkills(IncidentCommandEntity requestParameter,
                                                      DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        List<OpsAgentSkill> matchedSkills = opsAgentSkillService.match(
                requestParameter, dynamicContext.getRootCauseCandidates(), 3);
        return opsAgentSkillService.toRunbookMatches(matchedSkills);
    }

    private List<RunbookMatchEntity> mergeRunbookMatches(List<RunbookMatchEntity> runbookMatches,
                                                         List<RunbookMatchEntity> skillMatches) {
        List<RunbookMatchEntity> merged = new ArrayList<>();
        if (runbookMatches != null) {
            merged.addAll(runbookMatches);
        }
        if (skillMatches != null) {
            merged.addAll(skillMatches);
        }
        return merged;
    }

}


