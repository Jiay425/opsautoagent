package com.opsautoagent.domain.ops.agent.plan;

import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentInput;
import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentJsonSupport;
import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentOutput;
import com.opsautoagent.domain.ops.agent.chat.OpsMultiChatAgentService;
import com.opsautoagent.domain.ops.agent.memory.OpsHistoricalIncidentMemoryService;
import com.opsautoagent.domain.ops.agent.memory.OpsIncidentWorkingMemory;
import com.opsautoagent.domain.ops.agent.memory.OpsIncidentWorkingMemoryService;
import com.opsautoagent.domain.ops.agent.state.OpsIncidentState;
import com.opsautoagent.domain.ops.agent.skill.OpsAgentSkill;
import com.opsautoagent.domain.ops.agent.skill.OpsAgentSkillService;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class OpsAgentPlannerService {

    @Value("${ops.agent.max-tool-calls:12}")
    private int maxToolCalls;

    @Value("${ops.agent.chat.enabled:false}")
    private boolean chatAgentEnabled;

    @Value("${ops.agent.chat.required:false}")
    private boolean chatAgentRequired;

    @Resource
    private OpsAgentSkillService opsAgentSkillService;

    @Resource
    private OpsMultiChatAgentService opsMultiChatAgentService;

    @Resource
    private OpsIncidentWorkingMemoryService workingMemoryService;

    @Resource
    private OpsHistoricalIncidentMemoryService historicalIncidentMemoryService;

    public OpsInvestigationPlan plan(OpsAlertEventEntity alertEvent,
                                     IncidentCommandEntity command,
                                     OpsIncidentState state) {
        OpsInvestigationPlan ruleBasedPlan = buildRuleBasedPlan(alertEvent, command, state);
        if (!chatAgentEnabled) {
            return ruleBasedPlan;
        }
        OpsInvestigationPlan chatAgentPlan = tryBuildChatAgentPlan(alertEvent, command, state, ruleBasedPlan);
        if (chatAgentPlan != null) {
            return chatAgentPlan;
        }
        if (chatAgentRequired) {
            throw new IllegalStateException("Planner Chat Agent is required but unavailable or returned invalid output.");
        }
        return markPlannerFallback(ruleBasedPlan);
    }

    private OpsInvestigationPlan buildRuleBasedPlan(OpsAlertEventEntity alertEvent,
                                                    IncidentCommandEntity command,
                                                    OpsIncidentState state) {
        String alertType = classifyAlertType(alertEvent);
        List<OpsAgentSkill> matchedSkills = opsAgentSkillService.match(alertEvent, command, 3);
        List<String> hypotheses = buildHypotheses(alertType);
        List<Map<String, Object>> steps = buildSteps(alertType);
        appendSkillSteps(steps, matchedSkills);
        List<String> requiredTools = extractRequiredTools(steps);
        appendTools(requiredTools, opsAgentSkillService.recommendedTools(matchedSkills));
        List<String> expectedEvidence = buildExpectedEvidence(alertType);
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("maxToolCalls", maxToolCalls);
        budget.put("maxRounds", state.getMaxRounds());
        budget.put("timeoutSecondsPerTool", 15);

        String hypothesesJson = JSON.toJSONString(hypotheses);
        String stepsJson = JSON.toJSONString(steps);
        String requiredToolsJson = JSON.toJSONString(requiredTools);
        String expectedEvidenceJson = JSON.toJSONString(expectedEvidence);
        String budgetJson = JSON.toJSONString(budget);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("diagnosisId", command.getDiagnosisId());
        plan.put("round", state.getCurrentRound());
        plan.put("alertType", alertType);
        plan.put("hypotheses", hypotheses);
        plan.put("steps", steps);
        plan.put("requiredTools", requiredTools);
        plan.put("matchedSkills", matchedSkills);
        plan.put("expectedEvidence", expectedEvidence);
        plan.put("riskLevel", resolveRiskLevel(alertEvent));
        plan.put("budget", budget);

        LocalDateTime now = LocalDateTime.now();
        return OpsInvestigationPlan.builder()
                .planId("plan-" + UUID.randomUUID())
                .diagnosisId(command.getDiagnosisId())
                .stateId(state.getStateId())
                .round(state.getCurrentRound())
                .alertType(alertType)
                .hypothesesJson(hypothesesJson)
                .stepsJson(stepsJson)
                .requiredToolsJson(requiredToolsJson)
                .expectedEvidenceJson(expectedEvidenceJson)
                .riskLevel(resolveRiskLevel(alertEvent))
                .budgetJson(budgetJson)
                .planJson(JSON.toJSONString(plan))
                .plannerType("RULE_BASED")
                .createTime(now)
                .updateTime(now)
                .build();
    }

    private OpsInvestigationPlan tryBuildChatAgentPlan(OpsAlertEventEntity alertEvent,
                                                       IncidentCommandEntity command,
                                                       OpsIncidentState state,
                                                       OpsInvestigationPlan fallbackPlan) {
        OpsChatAgentOutput output = null;
        try {
            OpsIncidentWorkingMemory workingMemory = workingMemoryService.initialize(alertEvent, command, state, fallbackPlan);
            String historicalMemoryJson = historicalIncidentMemoryService.toPromptJson(
                    historicalIncidentMemoryService.querySimilar(alertEvent, command, 3));
            OpsChatAgentInput input = OpsChatAgentInput.builder()
                    .diagnosisId(command.getDiagnosisId())
                    .sessionId(command.getSessionId())
                    .serviceName(command.getServiceName())
                    .objective("Generate a minimal ops investigation plan. Return JSON only.")
                    .incidentContext(JSON.toJSONString(alertEvent))
                    .incidentStateJson(JSON.toJSONString(state))
                    .workingMemoryJson(workingMemoryService.toPromptJson(workingMemory))
                    .historicalMemoryJson(historicalMemoryJson)
                    .toolConstraintsJson(buildToolConstraintsJson(maxToolCalls, state.getMaxRounds(), false))
                    .planJson(fallbackPlan.getPlanJson())
                    .constraints(List.of("Use only supported tool names.", "Do not claim root cause in the plan."))
                    .metadata(Map.of(
                            "fallbackPlannerType", fallbackPlan.getPlannerType(),
                            "fallbackPlanId", fallbackPlan.getPlanId()
                    ))
                    .build();
            output = opsMultiChatAgentService.plan(input);
            if (output == null || !output.isSuccess()) {
                return null;
            }
            JSONObject json = OpsChatAgentJsonSupport.parseObject(output.getContent());
            String alertType = blankToDefault(json.getString("alertType"), fallbackPlan.getAlertType());
            List<String> hypotheses = stringList(json.getJSONArray("hypotheses"));
            if (hypotheses.isEmpty()) {
                hypotheses = JSON.parseArray(fallbackPlan.getHypothesesJson(), String.class);
            }
            List<Map<String, Object>> steps = stepList(json.getJSONArray("steps"));
            if (steps.isEmpty()) {
                steps = fallbackSteps(fallbackPlan);
            }
            List<String> requiredTools = stringList(json.getJSONArray("requiredTools"));
            if (requiredTools.isEmpty()) {
                requiredTools = extractRequiredTools(steps);
            }
            List<String> expectedEvidence = stringList(json.getJSONArray("expectedEvidence"));
            if (expectedEvidence.isEmpty()) {
                expectedEvidence = JSON.parseArray(fallbackPlan.getExpectedEvidenceJson(), String.class);
            }
            String riskLevel = blankToDefault(json.getString("riskLevel"), fallbackPlan.getRiskLevel());
            Map<String, Object> budget = new LinkedHashMap<>();
            budget.put("maxToolCalls", maxToolCalls);
            budget.put("maxRounds", state.getMaxRounds());
            budget.put("timeoutSecondsPerTool", 15);

            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("diagnosisId", command.getDiagnosisId());
            plan.put("round", state.getCurrentRound());
            plan.put("alertType", alertType);
            plan.put("hypotheses", hypotheses);
            plan.put("steps", steps);
            plan.put("requiredTools", requiredTools);
            plan.put("expectedEvidence", expectedEvidence);
            plan.put("riskLevel", riskLevel);
            plan.put("budget", budget);
            plan.put("plannerSource", "CHAT_AGENT");
            plan.put("chatAgent", chatAgentMetadata(output, json.getString("rationale"), null));

            LocalDateTime now = LocalDateTime.now();
            return OpsInvestigationPlan.builder()
                    .planId("plan-" + UUID.randomUUID())
                    .diagnosisId(command.getDiagnosisId())
                    .stateId(state.getStateId())
                    .round(state.getCurrentRound())
                    .alertType(alertType)
                    .hypothesesJson(JSON.toJSONString(hypotheses))
                    .stepsJson(JSON.toJSONString(steps))
                    .requiredToolsJson(JSON.toJSONString(requiredTools))
                    .expectedEvidenceJson(JSON.toJSONString(expectedEvidence))
                    .riskLevel(riskLevel)
                    .budgetJson(JSON.toJSONString(budget))
                    .planJson(JSON.toJSONString(plan))
                    .plannerType("CHAT_AGENT")
                    .createTime(now)
                    .updateTime(now)
                    .build();
        } catch (Exception ignore) {
            if (output != null && output.isSuccess()) {
                return buildNormalizedChatAgentPlan(command, state, fallbackPlan, output,
                        "Planner Chat Agent returned content, but JSON normalization failed.");
            }
            return null;
        }
    }

    private OpsInvestigationPlan buildNormalizedChatAgentPlan(IncidentCommandEntity command,
                                                              OpsIncidentState state,
                                                              OpsInvestigationPlan fallbackPlan,
                                                              OpsChatAgentOutput output,
                                                              String fallbackReason) {
        List<String> hypotheses = JSON.parseArray(fallbackPlan.getHypothesesJson(), String.class);
        List<Map<String, Object>> steps = fallbackSteps(fallbackPlan);
        List<String> requiredTools = JSON.parseArray(fallbackPlan.getRequiredToolsJson(), String.class);
        List<String> expectedEvidence = JSON.parseArray(fallbackPlan.getExpectedEvidenceJson(), String.class);
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("maxToolCalls", maxToolCalls);
        budget.put("maxRounds", state.getMaxRounds());
        budget.put("timeoutSecondsPerTool", 15);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("diagnosisId", command.getDiagnosisId());
        plan.put("round", state.getCurrentRound());
        plan.put("alertType", fallbackPlan.getAlertType());
        plan.put("hypotheses", hypotheses);
        plan.put("steps", steps);
        plan.put("requiredTools", requiredTools);
        plan.put("expectedEvidence", expectedEvidence);
        plan.put("riskLevel", fallbackPlan.getRiskLevel());
        plan.put("budget", budget);
        plan.put("plannerSource", "CHAT_AGENT_NORMALIZED");
        plan.put("chatAgent", chatAgentMetadata(output, null, fallbackReason));

        LocalDateTime now = LocalDateTime.now();
        return OpsInvestigationPlan.builder()
                .planId("plan-" + UUID.randomUUID())
                .diagnosisId(command.getDiagnosisId())
                .stateId(state.getStateId())
                .round(state.getCurrentRound())
                .alertType(fallbackPlan.getAlertType())
                .hypothesesJson(JSON.toJSONString(hypotheses))
                .stepsJson(JSON.toJSONString(steps))
                .requiredToolsJson(JSON.toJSONString(requiredTools))
                .expectedEvidenceJson(JSON.toJSONString(expectedEvidence))
                .riskLevel(fallbackPlan.getRiskLevel())
                .budgetJson(JSON.toJSONString(budget))
                .planJson(JSON.toJSONString(plan))
                .plannerType("CHAT_AGENT_NORMALIZED")
                .createTime(now)
                .updateTime(now)
                .build();
    }

    private OpsInvestigationPlan markPlannerFallback(OpsInvestigationPlan ruleBasedPlan) {
        try {
            JSONObject plan = JSON.parseObject(ruleBasedPlan.getPlanJson());
            plan.put("plannerSource", "RULE_BASED_FALLBACK");
            plan.put("chatAgent", chatAgentMetadata(null, null, "Chat Agent unavailable or invalid output."));
            ruleBasedPlan.setPlanJson(JSON.toJSONString(plan));
        } catch (Exception ignore) {
        }
        ruleBasedPlan.setPlannerType("RULE_BASED_FALLBACK");
        return ruleBasedPlan;
    }

    private Map<String, Object> chatAgentMetadata(OpsChatAgentOutput output, String rationale, String fallbackReason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", output == null ? "FALLBACK" : "CHAT_AGENT");
        metadata.put("clientBeanName", output == null ? "" : blankToEmpty(output.getClientBeanName()));
        metadata.put("resolutionSource", output == null ? "" : blankToEmpty(output.getResolutionSource()));
        metadata.put("costMillis", output == null || output.getCostMillis() == null ? 0L : output.getCostMillis());
        metadata.put("rationale", blankToEmpty(rationale));
        metadata.put("fallbackReason", blankToEmpty(fallbackReason));
        return metadata;
    }

    private String buildToolConstraintsJson(int maxToolCalls, int maxRounds, boolean reportOnly) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("supportedTools", List.of("query_prometheus", "query_elasticsearch", "query_skywalking_trace", "query_runbook"));
        constraints.put("maxToolCalls", Math.max(1, maxToolCalls));
        constraints.put("maxRounds", Math.max(1, maxRounds));
        constraints.put("readOnly", true);
        constraints.put("reportOnly", reportOnly);
        constraints.put("governance", List.of(
                "Use only supported logical tool names.",
                "Respect whitelist and per-diagnosis tool call budget.",
                "Do not request tools that already returned OK/NO_ANOMALY unless there is an entity or time-window mismatch.",
                "Historical memory and Runbook knowledge are not current direct evidence."
        ));
        return JSON.toJSONString(constraints);
    }

    private String classifyAlertType(OpsAlertEventEntity alertEvent) {
        String text = (
                blankToEmpty(alertEvent.getAlertRule()) + " " +
                        blankToEmpty(alertEvent.getLabelsJson()) + " " +
                        blankToEmpty(alertEvent.getAnnotationsJson())
        ).toLowerCase(Locale.ROOT);
        if (containsAny(text, "5xx", "500", "error", "exception")) {
            return "HTTP_5XX";
        }
        if (containsAny(text, "hikari", "connection_pool", "connection pool", "db", "database")) {
            return "DB_POOL";
        }
        if (containsAny(text, "jvm", "gc", "memory", "full gc")) {
            return "JVM_GC";
        }
        if (containsAny(text, "redis")) {
            return "REDIS_TIMEOUT";
        }
        if (containsAny(text, "mq", "backlog", "kafka", "rocketmq")) {
            return "MQ_BACKLOG";
        }
        if (containsAny(text, "rpc", "dubbo", "feign", "downstream")) {
            return "RPC_TIMEOUT";
        }
        if (containsAny(text, "latency", "slow", "timeout", "p95", "p99")) {
            return "LATENCY";
        }
        return "UNKNOWN";
    }

    private List<String> buildHypotheses(String alertType) {
        return switch (alertType) {
            case "HTTP_5XX" -> List.of("application_exception", "dependency_error", "database_error");
            case "LATENCY" -> List.of("downstream_timeout", "database_slow_query", "resource_saturation");
            case "DB_POOL" -> List.of("connection_pool_exhausted", "slow_sql", "connection_leak");
            case "JVM_GC" -> List.of("full_gc_pressure", "heap_pressure", "allocation_spike");
            case "REDIS_TIMEOUT" -> List.of("redis_timeout", "network_jitter", "hot_key_or_large_value");
            case "MQ_BACKLOG" -> List.of("consumer_lag", "producer_spike", "consumer_error");
            case "RPC_TIMEOUT" -> List.of("downstream_timeout", "provider_slow", "network_jitter");
            default -> List.of("application_exception", "dependency_timeout", "resource_bottleneck");
        };
    }

    private List<Map<String, Object>> buildSteps(String alertType) {
        List<Map<String, Object>> steps = new ArrayList<>();
        switch (alertType) {
            case "HTTP_5XX" -> {
                steps.add(step("step-1", "Logs Agent", "query_elasticsearch", "cluster error logs around the alert window", "error log cluster exists"));
                steps.add(step("step-2", "Trace Agent", "query_skywalking_trace", "find slow or error traces for affected endpoints", "trace samples explain failing path"));
                steps.add(step("step-3", "Metrics Agent", "query_prometheus", "check 5xx rate, qps, latency, jvm and db pool signals", "metrics confirm blast radius"));
            }
            case "LATENCY" -> {
                steps.add(step("step-1", "Metrics Agent", "query_prometheus", "check latency, qps, cpu, gc and pool usage", "latency and saturation indicators are identified"));
                steps.add(step("step-2", "Trace Agent", "query_skywalking_trace", "find slow spans and downstream calls", "slow span owner is identified"));
                steps.add(step("step-3", "Logs Agent", "query_elasticsearch", "search timeout and slow request logs", "logs support trace findings"));
            }
            case "DB_POOL" -> {
                steps.add(step("step-1", "Metrics Agent", "query_prometheus", "check hikari active, max, pending and timeout metrics", "pool pressure is quantified"));
                steps.add(step("step-2", "Trace Agent", "query_skywalking_trace", "find database spans and slow sql symptoms", "db span evidence exists"));
                steps.add(step("step-3", "Runbook Agent", "query_runbook", "match database connection pool runbook", "runbook provides mitigation"));
            }
            case "JVM_GC" -> {
                steps.add(step("step-1", "Metrics Agent", "query_prometheus", "check gc pause, memory used, cpu and thread metrics", "jvm pressure is quantified"));
                steps.add(step("step-2", "Logs Agent", "query_elasticsearch", "search oom, gc overhead and latency logs", "logs support jvm hypothesis"));
                steps.add(step("step-3", "Runbook Agent", "query_runbook", "match jvm full gc runbook", "runbook provides mitigation"));
            }
            case "REDIS_TIMEOUT" -> {
                steps.add(step("step-1", "Trace Agent", "query_skywalking_trace", "find redis spans and timeout path", "redis span evidence exists"));
                steps.add(step("step-2", "Logs Agent", "query_elasticsearch", "search redis timeout and connection errors", "redis error logs exist"));
                steps.add(step("step-3", "Runbook Agent", "query_runbook", "match redis timeout runbook", "runbook provides mitigation"));
            }
            case "MQ_BACKLOG" -> {
                steps.add(step("step-1", "Metrics Agent", "query_prometheus", "check consumer lag and processing metrics", "backlog is quantified"));
                steps.add(step("step-2", "Logs Agent", "query_elasticsearch", "search consumer error and retry logs", "consumer failure evidence exists"));
                steps.add(step("step-3", "Runbook Agent", "query_runbook", "match mq backlog runbook", "runbook provides mitigation"));
            }
            case "RPC_TIMEOUT" -> {
                steps.add(step("step-1", "Trace Agent", "query_skywalking_trace", "find downstream rpc timeout spans", "downstream owner and slow span are identified"));
                steps.add(step("step-2", "Logs Agent", "query_elasticsearch", "search rpc timeout and dependency error logs", "dependency timeout logs exist"));
                steps.add(step("step-3", "Metrics Agent", "query_prometheus", "check latency, qps and saturation metrics", "blast radius is quantified"));
                steps.add(step("step-4", "Runbook Agent", "query_runbook", "match rpc timeout runbook", "runbook provides mitigation"));
            }
            default -> {
                steps.add(step("step-1", "Metrics Agent", "query_prometheus", "collect baseline service health metrics", "baseline metrics collected"));
                steps.add(step("step-2", "Logs Agent", "query_elasticsearch", "search errors in alert window", "log evidence collected"));
                steps.add(step("step-3", "Trace Agent", "query_skywalking_trace", "inspect slow or error traces", "trace evidence collected"));
                steps.add(step("step-4", "Runbook Agent", "query_runbook", "match generic ops runbooks", "runbook evidence collected"));
            }
        }
        return steps;
    }

    private Map<String, Object> step(String stepId, String agentRole, String toolName, String queryIntent, String successCriteria) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepId", stepId);
        step.put("agentRole", agentRole);
        step.put("toolName", toolName);
        step.put("queryIntent", queryIntent);
        step.put("inputConstraints", "serviceName, alert time window, read-only evidence collection");
        step.put("successCriteria", successCriteria);
        step.put("timeoutSeconds", 15);
        return step;
    }

    private List<String> extractRequiredTools(List<Map<String, Object>> steps) {
        List<String> tools = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            String toolName = String.valueOf(step.get("toolName"));
            if (!tools.contains(toolName)) {
                tools.add(toolName);
            }
        }
        return tools;
    }

    private void appendTools(List<String> requiredTools, List<String> skillTools) {
        for (String tool : skillTools) {
            if (!requiredTools.contains(tool)) {
                requiredTools.add(tool);
            }
        }
    }

    private void appendSkillSteps(List<Map<String, Object>> steps, List<OpsAgentSkill> matchedSkills) {
        if (matchedSkills == null || matchedSkills.isEmpty()) {
            return;
        }
        List<String> existingTools = extractRequiredTools(steps);
        String skillNames = matchedSkills.stream().map(OpsAgentSkill::getName).toList().toString();
        int index = steps.size() + 1;
        for (String tool : opsAgentSkillService.recommendedTools(matchedSkills)) {
            if (existingTools.contains(tool)) {
                continue;
            }
            steps.add(step("skill-step-" + index++, agentRoleOf(tool), tool,
                    "collect evidence recommended by matched ops skills " + skillNames,
                    "skill-recommended evidence is collected or explicitly unavailable"));
            existingTools.add(tool);
        }
    }

    private String agentRoleOf(String tool) {
        return switch (tool) {
            case "query_prometheus" -> "Metrics Agent";
            case "query_elasticsearch" -> "Logs Agent";
            case "query_skywalking_trace" -> "Trace Agent";
            case "query_runbook" -> "Runbook Agent";
            default -> "Tool Agent";
        };
    }

    private List<String> buildExpectedEvidence(String alertType) {
        return switch (alertType) {
            case "HTTP_5XX" -> List.of("error_log_cluster", "error_trace_sample", "5xx_rate_metric");
            case "LATENCY" -> List.of("latency_metric", "slow_trace_sample", "timeout_or_slow_log");
            case "DB_POOL" -> List.of("hikari_pool_metric", "db_span_sample", "database_pool_runbook");
            case "JVM_GC" -> List.of("gc_pause_metric", "memory_metric", "jvm_runbook");
            case "REDIS_TIMEOUT" -> List.of("redis_trace_span", "redis_timeout_log", "redis_timeout_runbook");
            case "MQ_BACKLOG" -> List.of("mq_backlog_metric", "consumer_error_log", "mq_backlog_runbook");
            case "RPC_TIMEOUT" -> List.of("rpc_timeout_trace", "dependency_error_log", "rpc_timeout_runbook");
            default -> List.of("baseline_metrics", "error_logs", "trace_samples", "matched_runbook");
        };
    }

    private List<String> stringList(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (Object item : array) {
            if (item == null) {
                continue;
            }
            String value = String.valueOf(item).trim();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private List<Map<String, Object>> stepList(JSONArray array) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        int index = 1;
        for (Object item : array) {
            if (!(item instanceof JSONObject stepJson)) {
                continue;
            }
            String toolName = stepJson.getString("toolName");
            if (isBlank(toolName)) {
                continue;
            }
            values.add(step(
                    blankToDefault(stepJson.getString("stepId"), "chat-step-" + index++),
                    blankToDefault(stepJson.getString("agentRole"), agentRoleOf(toolName)),
                    toolName,
                    blankToDefault(stepJson.getString("queryIntent"), "collect evidence requested by Planner Chat Agent"),
                    blankToDefault(stepJson.getString("successCriteria"), "evidence is collected or explicitly unavailable")
            ));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fallbackSteps(OpsInvestigationPlan fallbackPlan) {
        try {
            return JSON.parseObject(fallbackPlan.getStepsJson(), List.class);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String resolveRiskLevel(OpsAlertEventEntity alertEvent) {
        String severity = blankToEmpty(alertEvent.getSeverity()).toUpperCase(Locale.ROOT);
        if ("P1".equals(severity) || "CRITICAL".equals(severity)) {
            return "HIGH";
        }
        if ("P2".equals(severity) || "WARNING".equals(severity)) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

