package com.opsautoagent.domain.codeops.agent.llm;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopDecision;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopRequest;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopStep;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopToolCall;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolRegistry;
import com.opsautoagent.domain.codeops.model.entity.EngineeringToolDefinitionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class CodeOpsAgentLoopModelClient implements AgentLoopModelClient {

    private static final int MAX_STEP_OUTPUT_LENGTH = 2400;

    private final CodeOpsCompatibleChatClient chatClient;
    private final EngineeringToolRegistry toolRegistry;

    public CodeOpsAgentLoopModelClient(CodeOpsCompatibleChatClient chatClient,
                                       EngineeringToolRegistry toolRegistry) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public AgentLoopDecision next(AgentLoopRequest request, List<AgentLoopStep> previousSteps) {
        if (!chatClient.available()) {
            return AgentLoopDecision.builder()
                    .thoughtSummary("LLM client unavailable")
                    .finalAnswer("Agent loop model client is unavailable: " + chatClient.unavailableReason())
                    .build();
        }
        String prompt = buildPrompt(request, previousSteps);
        String content = callWithSingleRetry(prompt);
        return parseDecision(content);
    }

    private String callWithSingleRetry(String prompt) {
        try {
            return chatClient.call(prompt);
        } catch (RuntimeException first) {
            log.warn("Agent loop model call failed once, retrying. error={}", first.getMessage());
            try {
                return chatClient.call(prompt);
            } catch (RuntimeException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private String buildPrompt(AgentLoopRequest request, List<AgentLoopStep> previousSteps) {
        JSONObject payload = new JSONObject(true);
        payload.put("goal", request == null ? "" : request.getGoal());
        payload.put("repository", request == null || request.getTask() == null ? "" : request.getTask().getRepository());
        payload.put("changeRef", request == null || request.getTask() == null ? "" : request.getTask().getChangeRef());
        payload.put("focusAreas", request == null || request.getTask() == null ? List.of() : request.getTask().getFocusAreas());
        int maxTurns = request == null ? 0 : request.getMaxTurns();
        int completedTurns = previousSteps == null ? 0 : previousSteps.stream()
                .map(AgentLoopStep::getTurnNo)
                .distinct()
                .toList()
                .size();
        payload.put("maxTurns", maxTurns);
        payload.put("completedTurns", completedTurns);
        payload.put("remainingTurns", Math.max(0, maxTurns - completedTurns));
        payload.put("availableTools", toolRegistry.listTools().stream().map(this::toolToMap).toList());
        payload.put("metadata", request == null || request.getMetadata() == null ? Map.of() : request.getMetadata());
        payload.put("previousSteps", previousSteps == null ? List.of() : previousSteps.stream().map(this::stepToMap).toList());

        return """
                You are the CodeOps agent loop planner inside an engineering diagnosis harness.
                Decide the next tool call(s) or produce a final answer.

                Rules:
                - Use only tools listed in availableTools.
                - Prefer read-only repository tools before command execution.
                - metadata.preLoopCodeContextPack is an initial evidence-backed code context pack. Use it as a starting point, but verify or expand it with tools when uncertainty remains.
                - Cite concrete files, methods, snippets, tool observations, or preLoopCodeContextPack facts in supportingCodeEvidence.
                - Keep tool arguments small and explicit.
                - If enough evidence exists, set finalAnswer and leave toolCalls empty.
                - If remainingTurns <= 1, do not call more tools. You must produce finalAnswer JSON from the evidence already collected.
                - Do not call more than 3 tools in a single turn.
                - finalAnswer must be a compact JSON object string, or a JSON object, using the schema below.
                - Return JSON only, no markdown fences, no extra prose.

                Required JSON schema:
                {
                  "thoughtSummary": "brief reason for this turn",
                  "toolCalls": [
                    {
                      "toolName": "repo.search_text",
                      "arguments": {
                        "repository": "...",
                        "queries": ["keyword"],
                        "maxMatches": 20
                      }
                    }
                  ],
                  "finalAnswer": ""
                }

                Required finalAnswer JSON schema when no more tools are needed:
                {
                  "summary": "brief evidence-based conclusion",
                  "fixStrategy": "CODE_FIX|CONFIG_CHANGE|SCALE_OR_RESOURCE|ROLLBACK|DEPENDENCY_INCIDENT|NEED_MORE_EVIDENCE|NO_CODE_FIX",
                  "scopeDecision": "STRICT_SINGLE_METHOD|MULTI_METHOD|FULL_FILE|CROSS_FILE|NO_CODE_FIX",
                  "rootCauseLocationType": "STACK_TOP|CALLEE|CALLER|CROSS_FILE|CONFIG|INFRA|DEPENDENCY|UNKNOWN",
                  "primarySymptomLocation": "ClassName.methodName or file path from stack/log/trace",
                  "directEvidenceFiles": ["files directly named by stack/log/trace"],
                  "relatedFiles": ["files reached by reading direct callers/callees"],
                  "rootCauseCandidateFiles": ["files most likely needing modification"],
                  "doNotModifyFiles": ["related files that should be observed but not changed"],
                  "targetFiles": ["root cause candidate source files, prefer rootCauseCandidateFiles"],
                  "targetMethods": ["ClassName.methodName or methodName"],
                  "suspectedRootCauseLocations": ["ClassName.methodName with reason if useful"],
                  "candidateScope": {
                    "targetFiles": ["candidate files BugFix may modify if justified"],
                    "targetMethods": ["candidate methods BugFix may modify if justified"],
                    "expandable": true,
                    "expansionAllowedWhen": ["direct callee/root cause evidence requires it"]
                  },
                  "supportingCodeEvidence": ["specific code facts backing the localization"],
                  "negativeEvidence": ["checked but not root-cause evidence"],
                  "reasoning": "why the repair scope and fix strategy are chosen",
                  "recommendedTests": ["src/test/java/... or test recommendation"],
                  "shouldEnterCodeRepair": true,
                  "localizationConfidence": "HIGH|MEDIUM|LOW",
                  "missingEvidence": ["remaining uncertainty"]
                }

                Runtime input:
                %s
                """.formatted(payload.toJSONString());
    }

    private Map<String, Object> toolToMap(EngineeringToolDefinitionEntity tool) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("toolName", tool.getToolName());
        map.put("description", tool.getDescription());
        map.put("category", tool.getCategory());
        map.put("riskLevel", tool.getRiskLevel());
        map.put("accessLevel", tool.getAccessLevel());
        map.put("sourceType", tool.getSourceType());
        map.put("timeoutMillis", tool.getTimeoutMillis());
        map.put("argumentSchema", tool.getArgumentSchema() == null ? Map.of() : tool.getArgumentSchema());
        return map;
    }

    private Map<String, Object> stepToMap(AgentLoopStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("turnNo", step.getTurnNo());
        map.put("toolName", step.getToolName());
        map.put("arguments", step.getArguments());
        map.put("permission", step.getPermissionDecision() == null ? "" : step.getPermissionDecision().getStatus());
        map.put("status", step.getToolResult() == null ? "" : step.getToolResult().getStatus());
        map.put("summary", step.getToolResult() == null ? "" : step.getToolResult().getSummary());
        map.put("output", abbreviate(step.getToolResult() == null ? null : String.valueOf(step.getToolResult().getOutput()),
                MAX_STEP_OUTPUT_LENGTH));
        return map;
    }

    private AgentLoopDecision parseDecision(String content) {
        try {
            JSONObject json = JSONObject.parseObject(extractJson(content));
            return AgentLoopDecision.builder()
                    .thoughtSummary(firstNonBlank(json.getString("thoughtSummary"), json.getString("thought_summary")))
                    .toolCalls(parseToolCalls(firstArray(json, "toolCalls", "tool_calls")))
                    .finalAnswer(firstNonBlank(finalAnswerValue(json.get("finalAnswer")),
                            finalAnswerValue(json.get("final_answer"))))
                    .build();
        } catch (Exception e) {
            log.warn("Parse agent loop decision failed. content={}", abbreviate(content, 1200), e);
            return AgentLoopDecision.builder()
                    .thoughtSummary("Failed to parse model JSON")
                    .finalAnswer("模型输出无法解析为 agent loop JSON：" + e.getMessage()
                            + "\n原始输出：" + abbreviate(content, 1200))
                    .build();
        }
    }

    private JSONArray firstArray(JSONObject json, String firstName, String secondName) {
        JSONArray first = json.getJSONArray(firstName);
        if (first != null) {
            return first;
        }
        return json.getJSONArray(secondName);
    }

    private List<AgentLoopToolCall> parseToolCalls(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<AgentLoopToolCall> calls = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null || isBlank(item.getString("toolName"))) {
                continue;
            }
            calls.add(AgentLoopToolCall.builder()
                    .toolCallId(firstNonBlank(item.getString("toolCallId"), "tool-call-" + UUID.randomUUID()))
                    .toolName(item.getString("toolName"))
                    .arguments(toMap(item.getJSONObject("arguments")))
                    .build());
        }
        return calls;
    }

    private String finalAnswerValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JSONObject object) {
            return object.toJSONString();
        }
        return String.valueOf(value);
    }

    private Map<String, Object> toMap(JSONObject json) {
        if (json == null || json.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        json.forEach(map::put);
        return map;
    }

    private String extractJson(String content) {
        if (content == null) {
            return "{}";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        int arrayStart = trimmed.indexOf('[');
        int arrayEnd = trimmed.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return "{\"toolCalls\":" + trimmed.substring(arrayStart, arrayEnd + 1) + ",\"finalAnswer\":\"\"}";
        }
        return trimmed;
    }

    private String firstNonBlank(String first, String fallback) {
        return isBlank(first) ? fallback : first;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

}
