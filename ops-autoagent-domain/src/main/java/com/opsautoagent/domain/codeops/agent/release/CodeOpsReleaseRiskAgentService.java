package com.opsautoagent.domain.codeops.agent.release;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.codeops.agent.llm.CodeOpsCompatibleChatClient;
import com.opsautoagent.domain.codeops.model.entity.ReleaseRiskReportEntity;
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
public class CodeOpsReleaseRiskAgentService {

    @Resource
    private CodeOpsCompatibleChatClient compatibleChatClient;

    @Value("${codeops.agent.release-risk.llm.enabled:true}")
    private boolean llmReleaseRiskEnabled;

    @Value("${codeops.agent.release-risk.max-knowledge:5}")
    private int maxKnowledge;

    public CodeOpsReleaseRiskAgentOutput analyze(CodeOpsReleaseRiskAgentInput input) {
        if (!llmReleaseRiskEnabled) {
            return CodeOpsReleaseRiskAgentOutput.unavailable("Release risk LLM agent is disabled.", input.getBaselineReport());
        }
        if (!compatibleChatClient.available()) {
            return CodeOpsReleaseRiskAgentOutput.unavailable(compatibleChatClient.unavailableReason(), input.getBaselineReport());
        }
        CodeOpsReleaseRiskAgentInput normalizedInput = normalize(input);
        String prompt = CodeOpsReleaseRiskPrompts.buildPrompt(normalizedInput);
        long start = System.currentTimeMillis();
        try {
            String content = compatibleChatClient.call(prompt);
            CodeOpsReleaseRiskAgentOutput parsed = parse(content, normalizedInput.getBaselineReport());
            parsed.setCostMillis(System.currentTimeMillis() - start);
            parsed.setRawContent(content == null ? "" : content);
            parsed.setCreateTime(LocalDateTime.now());
            return parsed;
        } catch (Exception e) {
            log.warn("CodeOps release risk LLM agent failed. taskId={}", input == null ? null : input.getTaskId(), e);
            return CodeOpsReleaseRiskAgentOutput.builder()
                    .success(false)
                    .fallback(true)
                    .report(input.getBaselineReport())
                    .reasoning(List.of())
                    .humanApprovalPoints(List.of("Release risk LLM failed: " + e.getMessage()))
                    .rawContent("")
                    .errorMessage(e.getMessage())
                    .costMillis(System.currentTimeMillis() - start)
                    .createTime(LocalDateTime.now())
                    .build();
        }
    }

    private CodeOpsReleaseRiskAgentInput normalize(CodeOpsReleaseRiskAgentInput input) {
        return CodeOpsReleaseRiskAgentInput.builder()
                .taskId(input.getTaskId())
                .taskType(input.getTaskType())
                .goal(input.getGoal())
                .repositoryPath(input.getRepositoryPath())
                .changeRef(input.getChangeRef())
                .diffSummary(input.getDiffSummary())
                .changedFiles(input.getChangedFiles() == null ? List.of() : input.getChangedFiles())
                .relatedTestFiles(input.getRelatedTestFiles() == null ? List.of() : input.getRelatedTestFiles())
                .opsEvidence(input.getOpsEvidence())
                .fixStrategy(input.getFixStrategy())
                .codeLocalization(input.getCodeLocalization())
                .patchGeneration(input.getPatchGeneration())
                .testVerification(input.getTestVerification())
                .reflectionFailures(input.getReflectionFailures() == null ? List.of() : input.getReflectionFailures())
                .knowledgeMatches(limit(input.getKnowledgeMatches(), maxKnowledge))
                .baselineReport(input.getBaselineReport())
                .build();
    }

    private CodeOpsReleaseRiskAgentOutput parse(String content, ReleaseRiskReportEntity baselineReport) {
        JSONObject object = OpsChatAgentJsonSupport.parseObject(content);
        ReleaseRiskReportEntity report = ReleaseRiskReportEntity.builder()
                .repositoryPath(baselineReport == null ? "" : baselineReport.getRepositoryPath())
                .changeRef(baselineReport == null ? "" : baselineReport.getChangeRef())
                .riskLevel(object.getString("riskLevel"))
                .impactScopes(stringList(object.getJSONArray("impactScopes")))
                .riskPoints(stringList(object.getJSONArray("riskPoints")))
                .regressionFocus(stringList(object.getJSONArray("regressionFocus")))
                .onlineObservationMetrics(stringList(object.getJSONArray("onlineObservationMetrics")))
                .rollbackFocus(stringList(object.getJSONArray("rollbackFocus")))
                .knowledgeReferences(stringList(object.getJSONArray("knowledgeReferences")))
                .build();
        return CodeOpsReleaseRiskAgentOutput.builder()
                .success(true)
                .fallback(false)
                .report(report)
                .reasoning(stringList(object.getJSONArray("reasoning")))
                .humanApprovalPoints(stringList(object.getJSONArray("humanApprovalPoints")))
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

}
