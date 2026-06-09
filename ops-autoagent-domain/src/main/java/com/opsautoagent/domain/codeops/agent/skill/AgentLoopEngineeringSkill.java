package com.opsautoagent.domain.codeops.agent.skill;

import com.opsautoagent.domain.codeops.agent.llm.CodeOpsAgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.llm.MockCodeOpsAgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopRequest;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopResult;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopStep;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopTraceItem;
import com.opsautoagent.domain.codeops.agent.runtime.AgentExecutionContext;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentLoopEngineeringSkill implements EngineeringSkill {

    public static final String SKILL_ID = "agent_loop_investigation";

    private static final Pattern JAVA_PATH_PATTERN = Pattern.compile("([A-Za-z0-9_./\\\\-]+\\.java)");

    private final AgentLoopService agentLoopService;
    private final CodeOpsAgentLoopModelClient modelClient;
    private final MockCodeOpsAgentLoopModelClient mockModelClient;

    public AgentLoopEngineeringSkill(AgentLoopService agentLoopService,
                                     CodeOpsAgentLoopModelClient modelClient,
                                     MockCodeOpsAgentLoopModelClient mockModelClient) {
        this.agentLoopService = agentLoopService;
        this.modelClient = modelClient;
        this.mockModelClient = mockModelClient;
    }

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("Agent Loop Investigation Skill")
                .description("Run a model-driven read-only tool loop for repository investigation and evidence collection.")
                .supportedTaskTypes(List.of("CODE_REVIEW", "ISSUE_TO_PATCH", "INCIDENT_TO_FIX", "RELEASE_RISK", "AGENT_LOOP_DEBUG"))
                .requiredTools(List.of("repo.create_snapshot", "repo.search_text", "repo.read_file_snippet", "repo.git_diff", "repo.maven"))
                .riskLevel("READ_ONLY")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        AgentLoopResult result = agentLoopService.run(AgentLoopRequest.builder()
                .goal(buildGoal(task))
                .task(task)
                .executionContext(resolveExecutionContext(task))
                .maxTurns(resolveMaxTurns(task))
                .metadata(task.getContext() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(task.getContext()))
                .build(), resolveModelClient(task));
        Map<String, Object> rawOutput = buildRawOutput(result);
        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status(result.isSuccess() ? "SUCCESS" : value(result.getStatus(), "FAILED"))
                .summary(result.isSuccess()
                        ? "Agent loop investigation completed: " + value(result.getFinalAnswer(), "")
                        : "Agent loop investigation stopped: " + value(result.getStopReason(), result.getStatus()))
                .evidence(buildEvidence(result))
                .nextActions(List.of("将 agent loop 调查摘要传递给后续代码定位、修复或风险分析阶段",
                        "必要时打开 includeSteps 或提高 maxTurns 获取更完整的工具调用证据"))
                .rawOutput(rawOutput)
                .build();
    }

    private String buildGoal(EngineeringTaskEntity task) {
        String goal = task == null ? "" : value(task.getGoal(), "");
        if (goal.isBlank()) {
            goal = "Investigate the repository and summarize relevant code and tests.";
        }
        return goal + "\n\nUse read-only tools first. Summarize target files, likely tests, and remaining uncertainty.";
    }

    private AgentExecutionContext resolveExecutionContext(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) {
            return null;
        }
        Object value = task.getContext().get("agentRuntimeContext");
        return value instanceof AgentExecutionContext context ? context : null;
    }

    private AgentLoopModelClient resolveModelClient(EngineeringTaskEntity task) {
        if (task != null && task.getContext() != null
                && Boolean.TRUE.equals(task.getContext().get("agentLoopDryRun"))) {
            return mockModelClient;
        }
        return modelClient;
    }

    private int resolveMaxTurns(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) {
            return 5;
        }
        Object value = task.getContext().get("agentLoopMaxTurns");
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 12));
        }
        try {
            return value == null ? 5 : Math.max(1, Math.min(Integer.parseInt(String.valueOf(value)), 12));
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private Map<String, Object> buildRawOutput(AgentLoopResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("phase", "PHASE_AGENT_LOOP_INVESTIGATION");
        output.put("status", result == null ? "FAILED" : result.getStatus());
        output.put("summary", result == null ? "" : result.getFinalAnswer());
        output.put("finalAnswer", result == null ? "" : result.getFinalAnswer());
        output.put("stopReason", result == null ? "" : result.getStopReason());
        output.put("turns", result == null ? 0 : result.getTurns());
        output.put("trace", result == null ? List.of() : result.getTrace());
        output.put("targetFiles", extractJavaPaths(result));
        output.put("recommendedTests", extractTestFiles(result));
        output.put("shouldEnterCodeRepair", false);
        output.put("strategyType", "READ_ONLY_INVESTIGATION");
        output.put("localizationConfidence", result != null && result.isSuccess() ? "MEDIUM" : "LOW");
        return output;
    }

    private List<String> buildEvidence(AgentLoopResult result) {
        if (result == null) {
            return List.of("Agent loop did not return a result.");
        }
        List<String> evidence = new ArrayList<>();
        evidence.add("Agent loop status: " + result.getStatus());
        evidence.add("Agent loop turns: " + result.getTurns());
        evidence.add("Agent loop final answer: " + value(result.getFinalAnswer(), ""));
        if (result.getTrace() != null) {
            result.getTrace().stream()
                    .map(item -> item.getToolName() + " -> " + item.getToolStatus() + " (" + item.getSummary() + ")")
                    .forEach(evidence::add);
        }
        return evidence;
    }

    private List<String> extractJavaPaths(AgentLoopResult result) {
        List<String> values = new ArrayList<>();
        if (result == null) {
            return values;
        }
        collectJavaPaths(values, result.getFinalAnswer());
        if (result.getTrace() != null) {
            for (AgentLoopTraceItem item : result.getTrace()) {
                collectJavaPaths(values, item.getOutputPreview());
            }
        }
        if (result.getSteps() != null) {
            for (AgentLoopStep step : result.getSteps()) {
                collectJavaPaths(values, step.getToolResult() == null ? "" : String.valueOf(step.getToolResult().getOutput()));
            }
        }
        return values.stream().distinct().limit(20).toList();
    }

    private List<String> extractTestFiles(AgentLoopResult result) {
        return extractJavaPaths(result).stream()
                .filter(path -> path.contains("/src/test/") || path.contains("\\src\\test\\") || path.endsWith("Test.java") || path.endsWith("Tests.java"))
                .distinct()
                .limit(20)
                .toList();
    }

    private void collectJavaPaths(List<String> values, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = JAVA_PATH_PATTERN.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1).replace('\\', '/'));
        }
    }

    private String value(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

}
