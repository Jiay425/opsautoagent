package com.opsautoagent.domain.codeops.agent.skill;

import com.opsautoagent.domain.codeops.agent.review.CodeOpsReviewAgentInput;
import com.opsautoagent.domain.codeops.agent.review.CodeOpsReviewAgentOutput;
import com.opsautoagent.domain.codeops.agent.review.CodeOpsReviewAgentService;
import com.opsautoagent.domain.codeops.agent.knowledge.EngineeringKnowledgeSearchService;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolGateway;
import com.opsautoagent.domain.codeops.model.entity.EngineeringKnowledgeMatchEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffContextEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffHunkEntity;
import com.opsautoagent.domain.codeops.model.entity.ReviewFindingEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PrReviewSkill implements EngineeringSkill {

    public static final String SKILL_ID = "pr_review";

    private final EngineeringToolGateway toolGateway;

    private final CodeOpsReviewAgentService reviewAgentService;

    private final EngineeringKnowledgeSearchService knowledgeSearchService;

    public PrReviewSkill(EngineeringToolGateway toolGateway,
                         CodeOpsReviewAgentService reviewAgentService,
                         EngineeringKnowledgeSearchService knowledgeSearchService) {
        this.toolGateway = toolGateway;
        this.reviewAgentService = reviewAgentService;
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("PR Review Skill")
                .description("Review code changes for correctness, stability, performance, transaction and test risks.")
                .supportedTaskTypes(List.of("CODE_REVIEW"))
                .requiredTools(List.of("artifact.generate_review_report"))
                .riskLevel("LOW_RISK_WRITE")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        RepoDiffContextEntity diffContext = toolGateway.loadDiffContext(task.getRepository(), task.getChangeRef(), task.getContext());
        List<ReviewFindingEntity> baselineFindings = review(diffContext, task);
        List<EngineeringKnowledgeMatchEntity> knowledgeMatches = knowledgeSearchService.search(
                task, diffContext.getChangedFiles(), baselineFindings, 5);
        CodeOpsReviewAgentOutput llmReview = reviewAgentService.review(CodeOpsReviewAgentInput.builder()
                .taskId(task.getTaskId())
                .taskType(task.getTaskType())
                .goal(task.getGoal())
                .repositoryPath(diffContext.getRepositoryPath())
                .changeRef(diffContext.getChangeRef())
                .focusAreas(task.getFocusAreas())
                .changedFiles(diffContext.getChangedFiles())
                .relatedTestFiles(diffContext.getRelatedTestFiles())
                .hunks(diffContext.getHunks())
                .baselineFindings(baselineFindings)
                .knowledgeMatches(knowledgeMatches)
                .build());
        List<ReviewFindingEntity> findings = effectiveFindings(baselineFindings, llmReview);
        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status(Boolean.TRUE.equals(diffContext.getDiffAvailable()) ? "SUCCESS" : "NO_DIFF")
                .summary(buildSummary(diffContext, baselineFindings, findings, llmReview, knowledgeMatches))
                .evidence(buildEvidence(diffContext, findings, knowledgeMatches))
                .nextActions(buildNextActions(findings, diffContext))
                .rawOutput(buildRawOutput(diffContext, baselineFindings, findings, llmReview, knowledgeMatches))
                .build();
    }

    private Map<String, Object> buildRawOutput(RepoDiffContextEntity diffContext,
                                               List<ReviewFindingEntity> baselineFindings,
                                               List<ReviewFindingEntity> findings,
                                               CodeOpsReviewAgentOutput llmReview,
                                               List<EngineeringKnowledgeMatchEntity> knowledgeMatches) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("phase", "PHASE_2_LLM_ENHANCED_REVIEW");
        output.put("changedFiles", diffContext.getChangedFiles() == null ? List.of() : diffContext.getChangedFiles());
        output.put("relatedTestFiles", diffContext.getRelatedTestFiles() == null ? List.of() : diffContext.getRelatedTestFiles());
        output.put("hunkCount", diffContext.getHunks() == null ? 0 : diffContext.getHunks().size());
        output.put("baselineFindings", baselineFindings);
        output.put("knowledgeMatches", knowledgeMatches == null ? List.of() : knowledgeMatches);
        output.put("llmReviewSuccess", llmReview.isSuccess());
        output.put("llmReviewFallback", llmReview.isFallback());
        output.put("llmReviewSummary", value(llmReview.getSummary()));
        output.put("llmReviewNotes", llmReview.getReviewNotes() == null ? List.of() : llmReview.getReviewNotes());
        output.put("llmReviewError", value(llmReview.getErrorMessage()));
        output.put("findings", findings);
        return output;
    }

    private String buildSummary(RepoDiffContextEntity diffContext,
                                List<ReviewFindingEntity> baselineFindings,
                                List<ReviewFindingEntity> findings,
                                CodeOpsReviewAgentOutput llmReview,
                                List<EngineeringKnowledgeMatchEntity> knowledgeMatches) {
        String mode = llmReview.isSuccess() ? "LLM 语义增强" : "规则化基线";
        String llmMessage = llmReview.isSuccess() ? "，LLM summary=" + value(llmReview.getSummary())
                : "，LLM 降级原因=" + value(llmReview.getErrorMessage());
        return "已完成 PR Review（" + mode + "）：baselineFindings=" + baselineFindings.size()
                + "，finalFindings=" + findings.size()
                + "，knowledgeMatches=" + (knowledgeMatches == null ? 0 : knowledgeMatches.size())
                + "；" + diffContext.getDiffSummary()
                + llmMessage;
    }

    private List<ReviewFindingEntity> effectiveFindings(List<ReviewFindingEntity> baselineFindings,
                                                        CodeOpsReviewAgentOutput llmReview) {
        if (llmReview != null && llmReview.isSuccess()
                && llmReview.getFindings() != null && !llmReview.getFindings().isEmpty()) {
            return llmReview.getFindings();
        }
        return baselineFindings;
    }

    private List<ReviewFindingEntity> review(RepoDiffContextEntity diffContext, EngineeringTaskEntity task) {
        List<ReviewFindingEntity> findings = new ArrayList<>();
        String diff = value(diffContext.getDiffText());
        String lowerDiff = diff.toLowerCase(Locale.ROOT);
        List<String> changedFiles = diffContext.getChangedFiles() == null ? List.of() : diffContext.getChangedFiles();

        if (!Boolean.TRUE.equals(diffContext.getDiffAvailable())) {
            findings.add(finding("LOW", "CONTEXT", "repository", null,
                    "未读取到可审查 diff",
                    "当前任务没有读取到 working tree 或指定 changeRef 的 diff，Review 只能输出空上下文提示。",
                    "确认是否存在未提交变更，或在 changeRef 中传入可比较的分支/commit。"));
            return findings;
        }

        if (diff.length() > 12_000 || changedFiles.size() > 12) {
            findings.add(finding("MEDIUM", "REVIEW_SCOPE", "diff", firstHunk(diffContext),
                    "变更范围较大",
                    "当前 diff 文件数或行数较多，单次 Review 容易遗漏跨模块影响。",
                    "建议拆分变更，或补充发布风险分析任务，重点检查调用链、配置和测试覆盖。"));
        }

        boolean touchesJava = changedFiles.stream().anyMatch(file -> file.endsWith(".java"));
        boolean hasTestChange = changedFiles.stream().anyMatch(file -> file.contains("/src/test/") || file.endsWith("Test.java") || file.endsWith("Tests.java"));
        boolean hasRelatedTest = diffContext.getRelatedTestFiles() != null && !diffContext.getRelatedTestFiles().isEmpty();
        if (touchesJava && !hasTestChange && !hasRelatedTest) {
            findings.add(finding("MEDIUM", "TEST_GAP", "src/test", firstJavaHunk(diffContext),
                    "变更缺少相关测试",
                    "本次 Java 代码变更没有检测到测试文件修改，也没有找到命名相关的现有测试。",
                    "补充覆盖核心分支、异常分支和回归场景的单元测试或集成测试。"));
        }

        if (containsAny(lowerDiff, "@transactional", "transactiontemplate", "commit", "rollback")) {
            findings.add(finding("MEDIUM", "TRANSACTION", "diff", findHunk(diffContext, "@transactional", "transactiontemplate", "commit", "rollback"),
                    "涉及事务边界",
                    "diff 中出现事务相关修改，需要关注事务范围、异常回滚和外部调用是否被包进事务。",
                    "检查是否存在事务内远程调用、消息发送、缓存更新或吞异常导致不回滚。"));
        }

        if (containsAny(lowerDiff, "redis", "cache", "caffeine", "guava cache", "@cacheable", "@cacheevict")) {
            findings.add(finding("MEDIUM", "CACHE_CONSISTENCY", "diff", findHunk(diffContext, "redis", "cache", "caffeine", "guava cache", "@cacheable", "@cacheevict"),
                    "涉及缓存逻辑",
                    "diff 中出现缓存相关修改，需要关注缓存 key、过期时间和写后失效策略。",
                    "补充缓存命中、缓存失效、并发更新和数据回源的测试或验证说明。"));
        }

        if (containsAny(lowerDiff, "thread.sleep", "new thread", "completablefuture", "executorservice", "parallelstream")) {
            findings.add(finding("MEDIUM", "CONCURRENCY", "diff", findHunk(diffContext, "thread.sleep", "new thread", "completablefuture", "executorservice", "parallelstream"),
                    "涉及并发/异步逻辑",
                    "diff 中出现线程、异步或并行流相关修改，需要关注线程池隔离、异常处理和上下文传递。",
                    "确认线程池容量、超时、异常日志、MDC/Trace 上下文以及任务取消策略。"));
        }

        if (containsAny(lowerDiff, "resttemplate", "webclient", "openfeign", "feign", "httpclient")) {
            findings.add(finding("MEDIUM", "DEPENDENCY_FAILURE", "diff", findHunk(diffContext, "resttemplate", "webclient", "openfeign", "feign", "httpclient"),
                    "涉及外部依赖调用",
                    "diff 中出现远程调用相关修改，需要关注超时、重试、熔断和失败降级。",
                    "确认超时配置、错误码处理、重试幂等性和下游不可用时的降级路径。"));
        }

        if (containsAny(lowerDiff, "catch (exception", "catch(exception", "printstacktrace")) {
            findings.add(finding("LOW", "ERROR_HANDLING", "diff", findHunk(diffContext, "catch (exception", "catch(exception", "printstacktrace"),
                    "异常处理可能过宽",
                    "diff 中出现宽泛异常捕获或直接打印堆栈，可能隐藏真实失败原因。",
                    "收窄异常类型，保留结构化日志，并确保必要异常继续向上抛出或转成明确错误码。"));
        }

        if (containsAny(lowerDiff, "todo", "fixme")) {
            findings.add(finding("LOW", "MAINTAINABILITY", "diff", findHunk(diffContext, "todo", "fixme"),
                    "存在 TODO/FIXME 标记",
                    "diff 中出现 TODO/FIXME，可能表示逻辑未闭环或后续风险未处理。",
                    "确认是否必须在本次变更前完成，或补充明确的跟踪任务。"));
        }

        if (findings.isEmpty()) {
            findings.add(finding("INFO", "BASELINE", "diff", firstHunk(diffContext),
                    "未命中规则化风险",
                    "当前规则基线没有发现明显事务、缓存、并发、依赖失败或测试缺口风险。",
                    "后续接入 LLM Reviewer 后继续结合代码语义、调用链和项目规范做深度审查。"));
        }
        return findings;
    }

    private List<String> buildEvidence(RepoDiffContextEntity diffContext,
                                       List<ReviewFindingEntity> findings,
                                       List<EngineeringKnowledgeMatchEntity> knowledgeMatches) {
        List<String> evidence = new ArrayList<>();
        evidence.add("变更摘要：" + diffContext.getDiffSummary());
        evidence.add("变更文件：" + list(diffContext.getChangedFiles()));
        evidence.add("相关测试：" + list(diffContext.getRelatedTestFiles()));
        evidence.add("Diff hunk 数：" + (diffContext.getHunks() == null ? 0 : diffContext.getHunks().size()));
        for (ReviewFindingEntity finding : findings) {
            evidence.add("[" + finding.getSeverity() + "][" + finding.getCategory() + "] "
                    + location(finding) + " " + finding.getTitle());
        }
        if (knowledgeMatches != null && !knowledgeMatches.isEmpty()) {
            for (EngineeringKnowledgeMatchEntity match : knowledgeMatches) {
                evidence.add("[KNOWLEDGE][" + match.getCategory() + "][" + match.getScore() + "] "
                        + match.getTitle() + " -> " + match.getPath());
            }
        }
        return evidence;
    }

    private List<String> buildNextActions(List<ReviewFindingEntity> findings, RepoDiffContextEntity diffContext) {
        List<String> actions = new ArrayList<>();
        actions.add("接入 LLM Reviewer 结合项目规范做语义审查");
        boolean hasTestGap = findings.stream().anyMatch(finding -> "TEST_GAP".equals(finding.getCategory()));
        if (hasTestGap) {
            actions.add("为本次变更补充回归测试");
        }
        if (diffContext.getChangedFiles() != null && diffContext.getChangedFiles().size() > 8) {
            actions.add("建议继续执行 Release Risk 分析");
        }
        return actions;
    }

    private ReviewFindingEntity finding(String severity, String category, String location, RepoDiffHunkEntity hunk,
                                        String title, String detail, String recommendation) {
        String filePath = hunk == null ? null : hunk.getFilePath();
        Integer startLine = hunk == null ? null : hunk.getNewStartLine();
        Integer endLine = hunk == null ? null : hunk.getNewEndLine();
        return ReviewFindingEntity.builder()
                .severity(severity)
                .category(category)
                .location(location)
                .filePath(filePath)
                .startLine(startLine)
                .endLine(endLine)
                .title(title)
                .detail(detail)
                .recommendation(recommendation)
                .build();
    }

    private RepoDiffHunkEntity findHunk(RepoDiffContextEntity diffContext, String... patterns) {
        if (diffContext.getHunks() == null || diffContext.getHunks().isEmpty()) {
            return null;
        }
        for (RepoDiffHunkEntity hunk : diffContext.getHunks()) {
            String hunkText = (value(hunk.getSnippet()) + "\n" + list(hunk.getAddedLines())).toLowerCase(Locale.ROOT);
            if (containsAny(hunkText, patterns)) {
                return hunk;
            }
        }
        return firstHunk(diffContext);
    }

    private RepoDiffHunkEntity firstJavaHunk(RepoDiffContextEntity diffContext) {
        if (diffContext.getHunks() == null || diffContext.getHunks().isEmpty()) {
            return null;
        }
        return diffContext.getHunks().stream()
                .filter(hunk -> hunk.getFilePath() != null && hunk.getFilePath().endsWith(".java"))
                .findFirst()
                .orElse(firstHunk(diffContext));
    }

    private RepoDiffHunkEntity firstHunk(RepoDiffContextEntity diffContext) {
        if (diffContext.getHunks() == null || diffContext.getHunks().isEmpty()) {
            return null;
        }
        return diffContext.getHunks().get(0);
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String list(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "无";
        }
        return String.join(", ", values);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String location(ReviewFindingEntity finding) {
        if (finding.getFilePath() == null) {
            return finding.getLocation();
        }
        if (finding.getStartLine() == null) {
            return finding.getFilePath();
        }
        return finding.getFilePath() + ":" + finding.getStartLine();
    }

}
