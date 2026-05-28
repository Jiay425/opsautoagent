package com.opsautoagent.domain.codeops.agent.review;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.codeops.agent.llm.CodeOpsCompatibleChatClient;
import com.opsautoagent.domain.codeops.model.entity.ReviewFindingEntity;
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
public class CodeOpsReviewAgentService {

    @Resource
    private CodeOpsCompatibleChatClient compatibleChatClient;

    @Value("${codeops.agent.review.llm.enabled:true}")
    private boolean llmReviewEnabled;

    @Value("${codeops.agent.review.max-hunks:8}")
    private int maxHunks;

    @Value("${codeops.agent.review.max-baseline-findings:8}")
    private int maxBaselineFindings;

    @Value("${codeops.agent.review.max-knowledge:5}")
    private int maxKnowledge;

    public CodeOpsReviewAgentOutput review(CodeOpsReviewAgentInput input) {
        if (!llmReviewEnabled) {
            return CodeOpsReviewAgentOutput.unavailable("CodeOps LLM reviewer is disabled.");
        }
        if (!compatibleChatClient.available()) {
            return CodeOpsReviewAgentOutput.unavailable(compatibleChatClient.unavailableReason());
        }

        CodeOpsReviewAgentInput normalizedInput = normalize(input);
        String prompt = CodeOpsReviewPrompts.buildPrompt(normalizedInput);
        long start = System.currentTimeMillis();
        try {
            String content = compatibleChatClient.call(prompt);
            CodeOpsReviewAgentOutput parsed = parse(content);
            parsed.setCostMillis(System.currentTimeMillis() - start);
            parsed.setRawContent(content == null ? "" : content);
            parsed.setCreateTime(LocalDateTime.now());
            return parsed;
        } catch (Exception e) {
            log.warn("CodeOps LLM reviewer failed. taskId={}", input == null ? null : input.getTaskId(), e);
            return CodeOpsReviewAgentOutput.builder()
                    .success(false)
                    .fallback(true)
                    .summary("")
                    .findings(List.of())
                    .reviewNotes(List.of())
                    .rawContent("")
                    .errorMessage(e.getMessage())
                    .costMillis(System.currentTimeMillis() - start)
                    .createTime(LocalDateTime.now())
                    .build();
        }
    }

    private CodeOpsReviewAgentInput normalize(CodeOpsReviewAgentInput input) {
        if (input == null) {
            return CodeOpsReviewAgentInput.builder().build();
        }
        return CodeOpsReviewAgentInput.builder()
                .taskId(input.getTaskId())
                .taskType(input.getTaskType())
                .goal(input.getGoal())
                .repositoryPath(input.getRepositoryPath())
                .changeRef(input.getChangeRef())
                .focusAreas(input.getFocusAreas())
                .changedFiles(input.getChangedFiles())
                .relatedTestFiles(input.getRelatedTestFiles())
                .hunks(limit(input.getHunks(), maxHunks))
                .baselineFindings(limit(input.getBaselineFindings(), maxBaselineFindings))
                .knowledgeMatches(limit(input.getKnowledgeMatches(), maxKnowledge))
                .build();
    }

    private CodeOpsReviewAgentOutput parse(String content) {
        JSONObject object = OpsChatAgentJsonSupport.parseObject(content);
        List<ReviewFindingEntity> findings = new ArrayList<>();
        JSONArray findingArray = object.getJSONArray("findings");
        if (findingArray != null) {
            for (int i = 0; i < findingArray.size(); i++) {
                findings.add(findingArray.getObject(i, ReviewFindingEntity.class));
            }
        }
        List<String> notes = new ArrayList<>();
        JSONArray noteArray = object.getJSONArray("reviewNotes");
        if (noteArray != null) {
            for (int i = 0; i < noteArray.size(); i++) {
                notes.add(noteArray.getString(i));
            }
        }
        return CodeOpsReviewAgentOutput.builder()
                .success(true)
                .fallback(false)
                .summary(object.getString("summary"))
                .findings(findings)
                .reviewNotes(notes)
                .rawContent(JSON.toJSONString(object))
                .createTime(LocalDateTime.now())
                .build();
    }

    private <T> List<T> limit(List<T> values, int maxSize) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxSize);
        return values.size() <= limit ? values : values.subList(0, limit);
    }

}
