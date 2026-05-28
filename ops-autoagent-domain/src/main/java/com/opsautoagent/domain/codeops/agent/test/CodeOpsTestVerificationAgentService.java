package com.opsautoagent.domain.codeops.agent.test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.codeops.agent.llm.CodeOpsCompatibleChatClient;
import com.opsautoagent.domain.codeops.model.entity.TestVerificationPlanEntity;
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
public class CodeOpsTestVerificationAgentService {

    @Resource
    private CodeOpsCompatibleChatClient compatibleChatClient;

    @Value("${codeops.agent.test-verification.llm.enabled:true}")
    private boolean llmTestVerificationEnabled;

    public CodeOpsTestVerificationAgentOutput plan(CodeOpsTestVerificationAgentInput input) {
        if (!llmTestVerificationEnabled) {
            return CodeOpsTestVerificationAgentOutput.unavailable("Test verification LLM agent is disabled.", input.getBaselinePlan());
        }
        if (!compatibleChatClient.available()) {
            return CodeOpsTestVerificationAgentOutput.unavailable(compatibleChatClient.unavailableReason(), input.getBaselinePlan());
        }
        long start = System.currentTimeMillis();
        try {
            String content = compatibleChatClient.call(CodeOpsTestVerificationPrompts.buildPrompt(input));
            CodeOpsTestVerificationAgentOutput parsed = parse(content, input.getBaselinePlan());
            parsed.setCostMillis(System.currentTimeMillis() - start);
            parsed.setRawContent(content == null ? "" : content);
            parsed.setCreateTime(LocalDateTime.now());
            return parsed;
        } catch (Exception e) {
            log.warn("CodeOps test verification LLM agent failed. taskId={}", input == null ? null : input.getTaskId(), e);
            return CodeOpsTestVerificationAgentOutput.builder()
                    .success(false)
                    .fallback(true)
                    .plan(input.getBaselinePlan())
                    .reasoning(List.of("Test verification LLM failed: " + e.getMessage()))
                    .rawContent("")
                    .errorMessage(e.getMessage())
                    .costMillis(System.currentTimeMillis() - start)
                    .createTime(LocalDateTime.now())
                    .build();
        }
    }

    private CodeOpsTestVerificationAgentOutput parse(String content, TestVerificationPlanEntity baselinePlan) {
        JSONObject object = OpsChatAgentJsonSupport.parseObject(content);
        TestVerificationPlanEntity plan = TestVerificationPlanEntity.builder()
                .repositoryPath(baselinePlan == null ? "" : baselinePlan.getRepositoryPath())
                .changeRef(baselinePlan == null ? "" : baselinePlan.getChangeRef())
                .changedFiles(baselinePlan == null ? List.of() : baselinePlan.getChangedFiles())
                .relatedTestFiles(baselinePlan == null ? List.of() : baselinePlan.getRelatedTestFiles())
                .recommendedTests(stringList(object.getJSONArray("recommendedTests")))
                .coverageGaps(stringList(object.getJSONArray("coverageGaps")))
                .mavenCommands(stringList(object.getJSONArray("mavenCommands")))
                .verificationNotes(stringList(object.getJSONArray("verificationNotes")))
                .testExecutionResults(List.of())
                .build();
        return CodeOpsTestVerificationAgentOutput.builder()
                .success(true)
                .fallback(false)
                .plan(plan)
                .reasoning(stringList(object.getJSONArray("reasoning")))
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

}
