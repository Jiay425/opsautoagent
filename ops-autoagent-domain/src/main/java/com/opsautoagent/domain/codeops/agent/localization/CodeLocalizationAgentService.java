package com.opsautoagent.domain.codeops.agent.localization;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.codeops.agent.llm.CodeOpsCompatibleChatClient;
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
public class CodeLocalizationAgentService {

    @Resource
    private CodeOpsCompatibleChatClient compatibleChatClient;

    @Value("${codeops.agent.localization.llm.enabled:true}")
    private boolean llmLocalizationEnabled;

    @Value("${codeops.agent.localization.max-snippets:6}")
    private int maxSnippets;

    public CodeLocalizationAgentOutput localize(CodeLocalizationAgentInput input) {
        if (!llmLocalizationEnabled) {
            return CodeLocalizationAgentOutput.unavailable("Code localization LLM agent is disabled.");
        }
        if (!compatibleChatClient.available()) {
            return CodeLocalizationAgentOutput.unavailable(compatibleChatClient.unavailableReason());
        }
        CodeLocalizationAgentInput normalizedInput = normalize(input);
        String prompt = CodeLocalizationPrompts.buildPrompt(normalizedInput);
        long start = System.currentTimeMillis();
        try {
            String content = compatibleChatClient.call(prompt);
            CodeLocalizationAgentOutput parsed = parse(content);
            parsed.setCostMillis(System.currentTimeMillis() - start);
            parsed.setRawContent(content == null ? "" : content);
            parsed.setCreateTime(LocalDateTime.now());
            return parsed;
        } catch (Exception e) {
            log.warn("Code localization LLM agent failed. taskId={}", input == null ? null : input.getTaskId(), e);
            return CodeLocalizationAgentOutput.builder()
                    .success(false)
                    .fallback(true)
                    .confidence("LOW")
                    .strategyType("NEED_MORE_EVIDENCE")
                    .shouldEnterCodeRepair(false)
                    .targetFiles(List.of())
                    .targetMethods(List.of())
                    .primarySuspectMethod("")
                    .candidateFiles(List.of())
                    .candidateMethods(List.of())
                    .scopeSuggestion("NEED_MORE_EVIDENCE")
                    .scopeConfidence("LOW")
                    .expandable(false)
                    .expansionBoundary(List.of())
                    .suspiciousLocations(List.of())
                    .relatedTests(List.of())
                    .reasoning(List.of())
                    .missingEvidence(List.of("LLM localization failed: " + e.getMessage()))
                    .rawContent("")
                    .errorMessage(e.getMessage())
                    .costMillis(System.currentTimeMillis() - start)
                    .createTime(LocalDateTime.now())
                    .build();
        }
    }

    private CodeLocalizationAgentInput normalize(CodeLocalizationAgentInput input) {
        if (input == null) {
            return CodeLocalizationAgentInput.builder().build();
        }
        return CodeLocalizationAgentInput.builder()
                .taskId(input.getTaskId())
                .taskType(input.getTaskType())
                .goal(input.getGoal())
                .repositoryPath(input.getRepositoryPath())
                .changeRef(input.getChangeRef())
                .opsDiagnosis(input.getOpsDiagnosis())
                .codeHints(input.getCodeHints() == null ? List.of() : input.getCodeHints())
                .codeSearchMatches(limit(input.getCodeSearchMatches(), 30))
                .codeSnippets(limit(input.getCodeSnippets(), maxSnippets))
                .evidenceGraph(input.getEvidenceGraph())
                .changedFiles(input.getChangedFiles() == null ? List.of() : input.getChangedFiles())
                .relatedTestFiles(input.getRelatedTestFiles() == null ? List.of() : input.getRelatedTestFiles())
                .build();
    }

    private CodeLocalizationAgentOutput parse(String content) {
        JSONObject object = OpsChatAgentJsonSupport.parseObject(content);
        return CodeLocalizationAgentOutput.builder()
                .success(true)
                .fallback(false)
                .confidence(object.getString("confidence"))
                .strategyType(value(object.getString("strategyType"), "NEED_MORE_EVIDENCE"))
                .shouldEnterCodeRepair(object.getBooleanValue("shouldEnterCodeRepair"))
                .targetFiles(stringList(object.getJSONArray("targetFiles")))
                .targetMethods(stringList(object.getJSONArray("targetMethods")))
                .primarySuspectMethod(value(object.getString("primarySuspectMethod"), ""))
                .candidateFiles(stringList(object.getJSONArray("candidateFiles")))
                .candidateMethods(stringList(object.getJSONArray("candidateMethods")))
                .scopeSuggestion(value(object.getString("scopeSuggestion"), "NO_CODE_FIX"))
                .scopeConfidence(value(object.getString("scopeConfidence"), value(object.getString("confidence"), "LOW")))
                .expandable(object.getBooleanValue("expandable"))
                .expansionBoundary(stringList(object.getJSONArray("expansionBoundary")))
                .suspiciousLocations(stringList(object.getJSONArray("suspiciousLocations")))
                .relatedTests(stringList(object.getJSONArray("relatedTests")))
                .reasoning(stringList(object.getJSONArray("reasoning")))
                .missingEvidence(stringList(object.getJSONArray("missingEvidence")))
                .rawContent(JSON.toJSONString(object))
                .createTime(LocalDateTime.now())
                .build();
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

    private <T> List<T> limit(List<T> values, int maxSize) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxSize);
        return values.size() <= limit ? values : values.subList(0, limit);
    }

    private String value(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

}
