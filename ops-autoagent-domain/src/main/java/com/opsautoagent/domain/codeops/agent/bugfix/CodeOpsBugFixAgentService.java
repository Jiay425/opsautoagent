package com.opsautoagent.domain.codeops.agent.bugfix;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.codeops.agent.llm.CodeOpsCompatibleChatClient;
import com.opsautoagent.domain.codeops.agent.llm.ModelRouter;
import com.opsautoagent.domain.codeops.agent.patch.ExactReplaceBlockPatchEntity;
import com.opsautoagent.domain.codeops.agent.patch.FileRewritePatchEntity;
import com.opsautoagent.domain.codeops.agent.recovery.ErrorRecoveryPolicy;
import com.opsautoagent.domain.codeops.agent.recovery.RecoveryDecision;
import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentJsonSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CodeOpsBugFixAgentService {

    @Resource
    private CodeOpsCompatibleChatClient compatibleChatClient;

    @Resource
    private ModelRouter modelRouter;

    @Resource
    private ErrorRecoveryPolicy errorRecoveryPolicy;

    @Value("${codeops.agent.bugfix.llm.enabled:true}")
    private boolean llmBugFixEnabled;

    @Value("${codeops.agent.bugfix.max-snippets:12}")
    private int maxSnippets;

    @Value("${codeops.agent.bugfix.max-knowledge:5}")
    private int maxKnowledge;

    public CodeOpsBugFixAgentOutput proposeFix(CodeOpsBugFixAgentInput input) {
        if (!llmBugFixEnabled) {
            return CodeOpsBugFixAgentOutput.unavailable("CodeOps LLM bugfix agent is disabled.");
        }
        if (!compatibleChatClient.available()) {
            return CodeOpsBugFixAgentOutput.unavailable(compatibleChatClient.unavailableReason());
        }

        CodeOpsBugFixAgentInput normalizedInput = normalize(input);
        String prompt = CodeOpsBugFixPrompts.buildPrompt(normalizedInput);

        // Model routing: flash for simple, pro for complex/escalation
        Map<String, Object> repairScope = input.getRepairScope();
        String goal = input.getGoal();
        int reflectionRound = input.getReflectionFailures() != null ? input.getReflectionFailures().size() : 0;
        int previousFlashFailures = countFlashFailures(input.getReflectionFailures());
        ModelRouter.ModelDecision modelDecision = modelRouter.decide(
                repairScope, goal, reflectionRound, previousFlashFailures);

        long start = System.currentTimeMillis();
        String content = null;
        try {
            content = compatibleChatClient.call(prompt, modelDecision.getModel(), modelDecision.getMaxTokens());
            CodeOpsBugFixAgentOutput parsed = parse(content);
            parsed.setModelRouting(Map.of(
                    "model", modelDecision.getModel(),
                    "modelTier", modelDecision.getModel().contains("flash") ? "flash" : "pro",
                    "reason", modelDecision.getReason(),
                    "reflectionRound", reflectionRound
            ));
            parsed.setLlmUsage(compatibleChatClient.lastUsage());
            parsed.setCostMillis(System.currentTimeMillis() - start);
            parsed.setRawContent(content == null ? "" : content);
            parsed.setCreateTime(LocalDateTime.now());
            return parsed;
        } catch (Exception e) {
            log.warn("CodeOps LLM bugfix agent failed. taskId={}", input == null ? null : input.getTaskId(), e);
            RecoveryDecision recovery = errorRecoveryPolicy.evaluate(e.getMessage(), reflectionRound);
            if (recovery.isRecoverable()) {
                log.info("LLM error is recoverable: type={}, action={}, wait={}s",
                        recovery.getRecoveryType(), recovery.getAction(), recovery.getWaitSeconds());
                try { Thread.sleep(recovery.getWaitSeconds() * 1000L); } catch (InterruptedException ignored) {}
            }
            boolean isFlashFailure = modelDecision.getModel().contains("flash");
            return CodeOpsBugFixAgentOutput.builder()
                    .success(false)
                    .fallback(true)
                    .rootCause("")
                    .confidence("LOW")
                    .targetFiles(List.of())
                .reasoning(List.of())
                .unifiedDiffPatch("")
                .fileRewrites(List.of())
                .exactReplaceBlocks(List.of())
                .testSuggestions(List.of())
                .mavenCommands(List.of())
                .testUnifiedDiffPatch("")
                .testFileRewrites(List.of())
                .riskNotes(List.of())
                    .scopeDecision(Map.of())
                    .rawContent(content == null ? "" : content)
                    .errorMessage(e.getMessage())
                    .modelRouting(Map.of(
                            "model", modelDecision.getModel(),
                            "modelTier", isFlashFailure ? "flash" : "pro",
                            "reason", modelDecision.getReason(),
                            "reflectionRound", reflectionRound
                    ))
                    .llmUsage(compatibleChatClient.lastUsage())
                    .costMillis(System.currentTimeMillis() - start)
                    .createTime(LocalDateTime.now())
                    .build();
        }
    }

    private int countFlashFailures(List<Object> reflectionFailures) {
        if (reflectionFailures == null || reflectionFailures.isEmpty()) return 0;
        int count = 0;
        for (Object f : reflectionFailures) {
            if (f instanceof Map<?, ?> map) {
                Object diagnostic = map.get("diagnostic");
                if (diagnostic instanceof Map<?, ?> d) {
                    Object model = d.get("model");
                    if (model != null && String.valueOf(model).contains("flash")) count++;
                }
            }
        }
        return count;
    }

    private CodeOpsBugFixAgentInput normalize(CodeOpsBugFixAgentInput input) {
        if (input == null) {
            return CodeOpsBugFixAgentInput.builder().build();
        }
        return CodeOpsBugFixAgentInput.builder()
                .taskId(input.getTaskId())
                .taskType(input.getTaskType())
                .goal(input.getGoal())
                .repositoryPath(input.getRepositoryPath())
                .changeRef(input.getChangeRef())
                .opsDiagnosis(input.getOpsDiagnosis())
                .diagnosisClues(input.getDiagnosisClues())
                .suspiciousLocations(input.getSuspiciousLocations())
                .repairScope(input.getRepairScope())
                .repairPlan(input.getRepairPlan())
                .codeSearchMatches(input.getCodeSearchMatches())
                .codeSnippets(limit(input.getCodeSnippets(), maxSnippets))
                .codeContextPack(input.getCodeContextPack())
                .knowledgeMatches(limit(input.getKnowledgeMatches(), maxKnowledge))
                .reflectionFailures(input.getReflectionFailures() == null ? List.of() : input.getReflectionFailures())
                .reflectionDiagnostics(input.getReflectionDiagnostics() == null ? List.of() : input.getReflectionDiagnostics())
                .memoryHints(input.getMemoryHints() == null ? List.of() : input.getMemoryHints())
                .build();
    }

    private CodeOpsBugFixAgentOutput parse(String content) {
        try {
            JSONObject object = OpsChatAgentJsonSupport.parseObject(content);
            return buildOutput(object, JSON.toJSONString(object));
        } catch (Exception e) {
            log.warn("CodeOps JSON parse failed, trying regex fallback. error={}", e.getMessage());
            return regexFallbackParse(content);
        }
    }

    private CodeOpsBugFixAgentOutput buildOutput(JSONObject object, String rawContent) {
        return CodeOpsBugFixAgentOutput.builder()
                .success(true)
                .fallback(false)
                .rootCause(object.getString("rootCause"))
                .confidence(object.getString("confidence"))
                .targetFiles(stringList(object.getJSONArray("targetFiles")))
                .reasoning(stringList(object.getJSONArray("reasoning")))
                .reflectionDiagnosis(mapObject(object.getJSONObject("reflectionDiagnosis")))
                .scopeDecision(mapObject(object.getJSONObject("scopeDecision")))
                .unifiedDiffPatch(object.getString("unifiedDiffPatch"))
                .fileRewrites(fileRewrites(object.getJSONArray("fileRewrites")))
                .exactReplaceBlocks(exactReplaceBlocks(object.getJSONArray("exactReplaceBlocks")))
                .testSuggestions(stringList(object.getJSONArray("testSuggestions")))
                .mavenCommands(stringList(object.getJSONArray("mavenCommands")))
                .testUnifiedDiffPatch(object.getString("testUnifiedDiffPatch"))
                .testFileRewrites(fileRewrites(object.getJSONArray("testFileRewrites")))
                .riskNotes(stringList(object.getJSONArray("riskNotes")))
                .rawContent(rawContent)
                .createTime(LocalDateTime.now())
                .build();
    }

    private CodeOpsBugFixAgentOutput regexFallbackParse(String content) {
        JSONObject object = new JSONObject();
        // Simple string fields
        object.put("rootCause", regexExtract(content, "rootCause"));
        object.put("confidence", regexExtract(content, "confidence"));
        // Array fields
        object.put("targetFiles", regexExtractArray(content, "targetFiles"));
        object.put("reasoning", regexExtractArray(content, "reasoning"));
        object.put("testSuggestions", regexExtractArray(content, "testSuggestions"));
        object.put("mavenCommands", regexExtractArray(content, "mavenCommands"));
        object.put("riskNotes", regexExtractArray(content, "riskNotes"));
        // Multi-line string fields
        object.put("unifiedDiffPatch", regexExtractMultiline(content, "unifiedDiffPatch"));
        object.put("testUnifiedDiffPatch", regexExtractMultiline(content, "testUnifiedDiffPatch"));
        // Object field
        object.put("reflectionDiagnosis", structuralExtractObject(content, "reflectionDiagnosis"));
        object.put("scopeDecision", structuralExtractObject(content, "scopeDecision"));
        // Array of objects — the critical one: extract fileRewrites
        object.put("fileRewrites", structuralExtractFileRewrites(content, "fileRewrites"));
        object.put("exactReplaceBlocks", structuralExtractExactReplaceBlocks(content, "exactReplaceBlocks"));
        object.put("testFileRewrites", structuralExtractFileRewrites(content, "testFileRewrites"));

        return buildOutput(object, content == null ? "" : content);
    }

    private String regexExtract(String content, String fieldName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(content == null ? "" : content);
        return m.find() ? m.group(1) : "";
    }

    private JSONArray regexExtractArray(String content, String fieldName) {
        JSONArray array = new JSONArray();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\"" + fieldName + "\"\\s*:\\s*\\[(.*?)\\]", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(content == null ? "" : content);
        if (!m.find()) return array;
        String inner = m.group(1);
        java.util.regex.Matcher items = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(inner);
        while (items.find()) array.add(items.group(1));
        return array;
    }

    private String regexExtractMultiline(String content, String fieldName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + fieldName + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", java.util.regex.Pattern.DOTALL)
                .matcher(content == null ? "" : content);
        return m.find() ? unescapeJsonString(m.group(1)) : "";
    }

    /**
     * Structural extraction for fileRewrites — brace-counting to find the array,
     * then extract each {filePath, newContent, reasoning} entry.
     */
    private JSONArray structuralExtractFileRewrites(String content, String fieldName) {
        JSONArray array = new JSONArray();
        if (content == null || content.isBlank()) return array;
        // Find the start of the field: "fileRewrites": [
        int fieldStart = content.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) return array;
        int arrayStart = content.indexOf("[", fieldStart);
        if (arrayStart < 0) return array;

        // Find matching ] by brace counting
        int arrayEnd = findMatchingBracket(content, arrayStart);
        if (arrayEnd < 0) return array;
        String arrayContent = content.substring(arrayStart + 1, arrayEnd).trim();
        if (arrayContent.isEmpty()) return array;

        // Extract each {...} object in the array
        int pos = 0;
        while (pos < arrayContent.length()) {
            int objStart = arrayContent.indexOf("{", pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(arrayContent, objStart);
            if (objEnd < 0) break;
            String objStr = arrayContent.substring(objStart, objEnd + 1);

            JSONObject entry = new JSONObject();
            entry.put("filePath", regexExtract(objStr, "filePath"));
            entry.put("newContent", regexExtractMultiline(objStr, "newContent"));
            entry.put("reasoning", regexExtract(objStr, "reasoning"));
            if (entry.getString("filePath") != null && !entry.getString("filePath").isBlank()) {
                array.add(entry);
            }
            pos = objEnd + 1;
        }
        return array;
    }

    private JSONArray structuralExtractExactReplaceBlocks(String content, String fieldName) {
        JSONArray array = new JSONArray();
        if (content == null || content.isBlank()) return array;
        int fieldStart = content.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) return array;
        int arrayStart = content.indexOf("[", fieldStart);
        if (arrayStart < 0) return array;
        int arrayEnd = findMatchingBracket(content, arrayStart);
        if (arrayEnd < 0) return array;
        String arrayContent = content.substring(arrayStart + 1, arrayEnd).trim();
        if (arrayContent.isEmpty()) return array;
        int pos = 0;
        while (pos < arrayContent.length()) {
            int objStart = arrayContent.indexOf("{", pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(arrayContent, objStart);
            if (objEnd < 0) break;
            String objStr = arrayContent.substring(objStart, objEnd + 1);
            JSONObject entry = new JSONObject();
            entry.put("filePath", regexExtract(objStr, "filePath"));
            entry.put("oldText", regexExtractMultiline(objStr, "oldText"));
            entry.put("newText", regexExtractMultiline(objStr, "newText"));
            entry.put("reasoning", regexExtract(objStr, "reasoning"));
            if (entry.getString("filePath") != null && !entry.getString("filePath").isBlank()) {
                array.add(entry);
            }
            pos = objEnd + 1;
        }
        return array;
    }

    private JSONObject structuralExtractObject(String content, String fieldName) {
        if (content == null || content.isBlank()) return new JSONObject();
        int fieldStart = content.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) return new JSONObject();
        int objStart = content.indexOf("{", fieldStart);
        if (objStart < 0) return new JSONObject();
        int objEnd = findMatchingBrace(content, objStart);
        if (objEnd < 0) return new JSONObject();
        String inner = content.substring(objStart + 1, objEnd);
        JSONObject result = new JSONObject();
        result.put("failureType", regexExtract(inner, "failureType"));
        result.put("mustFix", regexExtractArray(inner, "mustFix"));
        result.put("mustAvoid", regexExtractArray(inner, "mustAvoid"));
        return result;
    }

    private int findMatchingBracket(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private int findMatchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private String unescapeJsonString(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private List<String> stringList(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            values.add(array.getString(i));
        }
        return values;
    }

    private List<FileRewritePatchEntity> fileRewrites(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<FileRewritePatchEntity> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject object = array.getJSONObject(i);
            values.add(FileRewritePatchEntity.builder()
                    .filePath(object.getString("filePath"))
                    .newContent(object.getString("newContent"))
                    .reasoning(object.getString("reasoning"))
                    .build());
        }
        return values;
    }

    private List<ExactReplaceBlockPatchEntity> exactReplaceBlocks(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<ExactReplaceBlockPatchEntity> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject object = array.getJSONObject(i);
            if (object == null || object.getString("filePath") == null || object.getString("filePath").isBlank()) {
                continue;
            }
            values.add(ExactReplaceBlockPatchEntity.builder()
                    .filePath(object.getString("filePath"))
                    .oldText(object.getString("oldText"))
                    .newText(object.getString("newText") == null ? "" : object.getString("newText"))
                    .reasoning(object.getString("reasoning"))
                    .build());
        }
        return values;
    }

    private java.util.Map<String, Object> mapObject(JSONObject object) {
        if (object == null || object.isEmpty()) {
            return java.util.Map.of();
        }
        return new java.util.LinkedHashMap<>(object);
    }

    private <T> List<T> limit(List<T> values, int maxSize) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxSize);
        return values.size() <= limit ? values : values.subList(0, limit);
    }

}
