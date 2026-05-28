package com.opsautoagent.domain.codeops.agent.testpatch;

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
public class CodeOpsTestPatchAgentService {

    @Resource
    private CodeOpsCompatibleChatClient compatibleChatClient;

    @Value("${codeops.agent.test-patch.llm.enabled:true}")
    private boolean llmTestPatchEnabled;

    @Value("${codeops.agent.test-patch.max-snippets:4}")
    private int maxSnippets;

    public CodeOpsTestPatchAgentOutput proposeTestPatch(CodeOpsTestPatchAgentInput input) {
        if (!llmTestPatchEnabled) {
            return CodeOpsTestPatchAgentOutput.unavailable("Test patch LLM agent is disabled.");
        }
        if (!compatibleChatClient.available()) {
            return CodeOpsTestPatchAgentOutput.unavailable(compatibleChatClient.unavailableReason());
        }
        CodeOpsTestPatchAgentInput normalizedInput = normalize(input);
        long start = System.currentTimeMillis();
        try {
            String content = compatibleChatClient.call(CodeOpsTestPatchPrompts.buildPrompt(normalizedInput));
            CodeOpsTestPatchAgentOutput parsed = parse(content);
            parsed.setCostMillis(System.currentTimeMillis() - start);
            parsed.setRawContent(content == null ? "" : content);
            parsed.setCreateTime(LocalDateTime.now());
            return parsed;
        } catch (Exception e) {
            log.warn("CodeOps test patch agent failed. taskId={}", input == null ? null : input.getTaskId(), e);
            return CodeOpsTestPatchAgentOutput.builder()
                    .success(false)
                    .fallback(true)
                    .targetTestFiles(List.of())
                    .reasoning(List.of())
                .unifiedDiffPatch("")
                .fileRewrites(List.of())
                .rawContent("")
                    .errorMessage(e.getMessage())
                    .costMillis(System.currentTimeMillis() - start)
                    .createTime(LocalDateTime.now())
                    .build();
        }
    }

    private CodeOpsTestPatchAgentInput normalize(CodeOpsTestPatchAgentInput input) {
        if (input == null) {
            return CodeOpsTestPatchAgentInput.builder().build();
        }
        return CodeOpsTestPatchAgentInput.builder()
                .taskId(input.getTaskId())
                .taskType(input.getTaskType())
                .goal(input.getGoal())
                .repositoryPath(input.getRepositoryPath())
                .relatedTestFiles(input.getRelatedTestFiles() == null ? List.of() : input.getRelatedTestFiles())
                .codeLocalization(input.getCodeLocalization())
                .patchGeneration(input.getPatchGeneration())
                .testSnippets(limit(input.getTestSnippets(), maxSnippets))
                .build();
    }

    private CodeOpsTestPatchAgentOutput parse(String content) {
        JSONObject object = OpsChatAgentJsonSupport.parseObject(content);
        return CodeOpsTestPatchAgentOutput.builder()
                .success(true)
                .fallback(false)
                .targetTestFiles(stringList(object.getJSONArray("targetTestFiles")))
                .reasoning(stringList(object.getJSONArray("reasoning")))
                .unifiedDiffPatch(object.getString("unifiedDiffPatch"))
                .fileRewrites(fileRewrites(object.getJSONArray("fileRewrites")))
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

    private <T> List<T> limit(List<T> values, int maxSize) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxSize);
        return values.size() <= limit ? values : values.subList(0, limit);
    }

}
