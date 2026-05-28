package com.opsautoagent.domain.codeops.agent.bugfix;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.codeops.agent.llm.CodeOpsCompatibleChatClient;
import com.opsautoagent.domain.codeops.agent.patch.FileRewritePatchEntity;
import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentJsonSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CodeOpsBugFixAgentService {

    @Resource
    private CodeOpsCompatibleChatClient compatibleChatClient;

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
        long start = System.currentTimeMillis();
        String content = null;
        try {
            content = compatibleChatClient.call(prompt);
            CodeOpsBugFixAgentOutput parsed = parse(content);
            parsed.setCostMillis(System.currentTimeMillis() - start);
            parsed.setRawContent(content == null ? "" : content);
            parsed.setCreateTime(LocalDateTime.now());
            return parsed;
        } catch (Exception e) {
            log.warn("CodeOps LLM bugfix agent failed. taskId={}", input == null ? null : input.getTaskId(), e);
            return CodeOpsBugFixAgentOutput.builder()
                    .success(false)
                    .fallback(true)
                    .rootCause("")
                    .confidence("LOW")
                    .targetFiles(List.of())
                    .reasoning(List.of())
                .unifiedDiffPatch("")
                .fileRewrites(List.of())
                .testSuggestions(List.of())
                .mavenCommands(List.of())
                .testUnifiedDiffPatch("")
                .testFileRewrites(List.of())
                    .riskNotes(List.of())
                    .rawContent(content == null ? "" : content)
                    .errorMessage(e.getMessage())
                    .costMillis(System.currentTimeMillis() - start)
                    .createTime(LocalDateTime.now())
                    .build();
        }
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
                .codeSearchMatches(input.getCodeSearchMatches())
                .codeSnippets(limit(input.getCodeSnippets(), maxSnippets))
                .knowledgeMatches(limit(input.getKnowledgeMatches(), maxKnowledge))
                .reflectionFailures(input.getReflectionFailures() == null ? List.of() : input.getReflectionFailures())
                .reflectionDiagnostics(input.getReflectionDiagnostics() == null ? List.of() : input.getReflectionDiagnostics())
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
                .unifiedDiffPatch(object.getString("unifiedDiffPatch"))
                .fileRewrites(fileRewrites(object.getJSONArray("fileRewrites")))
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
        object.put("rootCause", regexExtract(content, "rootCause"));
        object.put("confidence", regexExtract(content, "confidence"));
        object.put("unifiedDiffPatch", regexExtractMultiline(content, "unifiedDiffPatch"));
        object.put("testUnifiedDiffPatch", regexExtractMultiline(content, "testUnifiedDiffPatch"));
        return buildOutput(object, content == null ? "" : content);
    }

    private String regexExtract(String content, String fieldName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(content == null ? "" : content);
        return m.find() ? m.group(1) : "";
    }

    private String regexExtractMultiline(String content, String fieldName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + fieldName + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", java.util.regex.Pattern.DOTALL)
                .matcher(content == null ? "" : content);
        return m.find() ? unescapeJsonString(m.group(1)) : "";
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
