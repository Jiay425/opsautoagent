package com.opsautoagent.domain.ops.agent.plan;

import com.opsautoagent.domain.ops.adapter.repository.IOpsAgentRepository;
import com.opsautoagent.domain.ops.agent.review.OpsEvidenceReviewResult;
import com.opsautoagent.domain.ops.agent.state.OpsAgentStateService;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.service.execute.DefaultOpsAgentExecuteStrategyFactory;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OpsAgentPlanExecutionService {

    private static final String PLAN_KEY = "ops_investigation_plan";
    private static final String PLANNED_TOOLS_KEY = "ops_planned_tools";
    private static final String TOOL_HISTORY_KEY = "ops_tool_history";

    @Value("${ops.agent.plan-driven.enabled:true}")
    private boolean planDrivenEnabled;

    @Resource
    private IOpsAgentRepository opsAgentRepository;

    @Resource
    private OpsAgentStateService opsAgentStateService;

    public void loadPlan(IncidentCommandEntity command,
                         DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        if (command == null || dynamicContext == null) {
            return;
        }
        OpsInvestigationPlan plan = opsAgentRepository.queryLatestPlanByDiagnosisId(command.getDiagnosisId());
        if (plan == null) {
            return;
        }
        dynamicContext.setValue(PLAN_KEY, plan);
        dynamicContext.setValue(PLANNED_TOOLS_KEY, parseTools(plan.getRequiredToolsJson()));
        dynamicContext.setValue(TOOL_HISTORY_KEY, new ArrayList<Map<String, Object>>());
    }

    public boolean shouldExecute(DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext, String toolName) {
        if (!planDrivenEnabled || dynamicContext == null || isBlank(toolName)) {
            return true;
        }
        Set<String> plannedTools = dynamicContext.getValue(PLANNED_TOOLS_KEY);
        if (plannedTools == null || plannedTools.isEmpty()) {
            return true;
        }
        return plannedTools.contains(toolName);
    }

    public void addSupplementalTools(DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                     List<String> toolNames) {
        if (dynamicContext == null || toolNames == null || toolNames.isEmpty()) {
            return;
        }
        Set<String> plannedTools = dynamicContext.getValue(PLANNED_TOOLS_KEY);
        if (plannedTools == null) {
            plannedTools = new LinkedHashSet<>();
            dynamicContext.setValue(PLANNED_TOOLS_KEY, plannedTools);
        }
        for (String toolName : toolNames) {
            if (!isBlank(toolName)) {
                plannedTools.add(toolName);
            }
        }
    }

    public OpsInvestigationPlan createSupplementalPlan(IncidentCommandEntity command,
                                                       DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                                       OpsEvidenceReviewResult reviewResult,
                                                       int supplementRound,
                                                       int maxRounds) {
        if (command == null || dynamicContext == null || reviewResult == null
                || reviewResult.getRequiredTools() == null || reviewResult.getRequiredTools().isEmpty()
                || supplementRound > Math.max(1, maxRounds)) {
            return null;
        }
        OpsInvestigationPlan parentPlan = currentPlan(command, dynamicContext);
        if (parentPlan == null || parentPlan.getRound() == null || supplementRound <= parentPlan.getRound()) {
            return null;
        }
        List<String> requiredTools = normalizeTools(reviewResult.getRequiredTools());
        if (requiredTools.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        int index = 1;
        for (String toolName : requiredTools) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepId", "supplement-" + supplementRound + "-" + index++);
            step.put("agentRole", agentRoleOf(toolName));
            step.put("toolName", toolName);
            step.put("queryIntent", "supplement missing evidence requested by Evidence Reviewer");
            step.put("inputConstraints", "serviceName, alert time window, missing evidence only, read-only evidence collection");
            step.put("successCriteria", "missing evidence is collected or explicitly marked unavailable");
            step.put("reason", reviewResult.getMissingEvidence());
            step.put("sourceReviewStatus", reviewResult.getStatus());
            step.put("timeoutSeconds", 15);
            steps.add(step);
        }
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("maxToolCalls", requiredTools.size());
        budget.put("maxRounds", maxRounds);
        budget.put("timeoutSecondsPerTool", 15);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("diagnosisId", command.getDiagnosisId());
        plan.put("round", supplementRound);
        plan.put("parentPlanId", parentPlan.getPlanId());
        plan.put("alertType", parentPlan.getAlertType());
        plan.put("hypotheses", List.of("missing_evidence_supplement"));
        plan.put("steps", steps);
        plan.put("requiredTools", requiredTools);
        plan.put("expectedEvidence", reviewResult.getMissingEvidence());
        plan.put("reviewStatus", reviewResult.getStatus());
        plan.put("reviewRound", reviewResult.getRound());
        plan.put("riskLevel", parentPlan.getRiskLevel());
        plan.put("budget", budget);

        LocalDateTime now = LocalDateTime.now();
        OpsInvestigationPlan supplementalPlan = OpsInvestigationPlan.builder()
                .planId("plan-" + UUID.randomUUID())
                .diagnosisId(command.getDiagnosisId())
                .stateId(parentPlan.getStateId())
                .round(supplementRound)
                .alertType(parentPlan.getAlertType())
                .hypothesesJson(JSON.toJSONString(List.of("missing_evidence_supplement")))
                .stepsJson(JSON.toJSONString(steps))
                .requiredToolsJson(JSON.toJSONString(requiredTools))
                .expectedEvidenceJson(JSON.toJSONString(reviewResult.getMissingEvidence()))
                .riskLevel(parentPlan.getRiskLevel())
                .budgetJson(JSON.toJSONString(budget))
                .planJson(JSON.toJSONString(plan))
                .plannerType("REVIEWER_SUPPLEMENT")
                .createTime(now)
                .updateTime(now)
                .build();
        opsAgentRepository.saveInvestigationPlan(supplementalPlan);
        dynamicContext.setValue(PLAN_KEY, supplementalPlan);
        dynamicContext.setValue(PLANNED_TOOLS_KEY, new LinkedHashSet<>(requiredTools));
        return supplementalPlan;
    }

    public void recordDecision(IncidentCommandEntity command,
                               DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                               String toolName,
                               String agentRole,
                               String decision,
                               String reason) {
        if (command == null || dynamicContext == null) {
            return;
        }
        List<Map<String, Object>> history = dynamicContext.getValue(TOOL_HISTORY_KEY);
        if (history == null) {
            history = new ArrayList<>();
            dynamicContext.setValue(TOOL_HISTORY_KEY, history);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("time", LocalDateTime.now().toString());
        item.put("toolName", toolName);
        item.put("agentRole", agentRole);
        item.put("decision", decision);
        item.put("reason", reason);
        OpsInvestigationPlan currentPlan = dynamicContext.getValue(PLAN_KEY);
        if (currentPlan != null) {
            item.put("planId", currentPlan.getPlanId());
            item.put("round", currentPlan.getRound());
        }
        history.add(item);
        opsAgentStateService.updateToolHistory(command.getDiagnosisId(), JSON.toJSONString(history));
    }

    private Set<String> parseTools(String requiredToolsJson) {
        Set<String> tools = new LinkedHashSet<>();
        if (isBlank(requiredToolsJson)) {
            return tools;
        }
        try {
            List<String> values = JSON.parseArray(requiredToolsJson, String.class);
            if (values != null) {
                tools.addAll(values);
            }
        } catch (Exception ignore) {
        }
        return tools;
    }

    private OpsInvestigationPlan currentPlan(IncidentCommandEntity command,
                                             DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        OpsInvestigationPlan plan = dynamicContext.getValue(PLAN_KEY);
        if (plan != null) {
            return plan;
        }
        OpsInvestigationPlan latest = opsAgentRepository.queryLatestPlanByDiagnosisId(command.getDiagnosisId());
        if (latest != null) {
            dynamicContext.setValue(PLAN_KEY, latest);
        }
        return latest;
    }

    private List<String> normalizeTools(List<String> toolNames) {
        List<String> normalized = new ArrayList<>();
        for (String toolName : toolNames) {
            if (!isBlank(toolName) && !normalized.contains(toolName)) {
                normalized.add(toolName);
            }
        }
        return normalized;
    }

    private String agentRoleOf(String toolName) {
        return switch (toolName) {
            case "query_prometheus" -> "Metrics Agent";
            case "query_elasticsearch" -> "Logs Agent";
            case "query_skywalking_trace" -> "Trace Agent";
            case "query_runbook" -> "Runbook Agent";
            default -> "Tool Agent";
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

