package com.opsautoagent.domain.codeops.agent.skill;

import com.opsautoagent.domain.codeops.agent.knowledge.EngineeringKnowledgeSearchService;
import com.opsautoagent.domain.codeops.agent.release.CodeOpsReleaseRiskAgentInput;
import com.opsautoagent.domain.codeops.agent.release.CodeOpsReleaseRiskAgentOutput;
import com.opsautoagent.domain.codeops.agent.release.CodeOpsReleaseRiskAgentService;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolGateway;
import com.opsautoagent.domain.codeops.model.entity.EngineeringKnowledgeMatchEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.ReleaseRiskReportEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffContextEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffHunkEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ReleaseRiskSkill implements EngineeringSkill {

    public static final String SKILL_ID = "release_risk_analysis";

    private static final int MAX_RISK_POINTS = 12;

    private final EngineeringToolGateway toolGateway;

    private final EngineeringKnowledgeSearchService knowledgeSearchService;

    private final CodeOpsReleaseRiskAgentService releaseRiskAgentService;

    public ReleaseRiskSkill(EngineeringToolGateway toolGateway,
                            EngineeringKnowledgeSearchService knowledgeSearchService,
                            CodeOpsReleaseRiskAgentService releaseRiskAgentService) {
        this.toolGateway = toolGateway;
        this.knowledgeSearchService = knowledgeSearchService;
        this.releaseRiskAgentService = releaseRiskAgentService;
    }

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("Release Risk Analysis Skill")
                .description("Analyze release impact, regression focus, online observation metrics and rollback concerns from repo diff and engineering knowledge.")
                .supportedTaskTypes(List.of("RELEASE_RISK", "CODE_REVIEW", "INCIDENT_TO_FIX"))
                .requiredTools(List.of("repo.git_diff", "repo.find_tests", "knowledge.search"))
                .riskLevel("READ_ONLY")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        RepoDiffContextEntity diffContext = toolGateway.loadDiffContext(task.getRepository(), task.getChangeRef(), task.getContext());
        List<EngineeringKnowledgeMatchEntity> knowledgeMatches = knowledgeSearchService.search(
                task,
                safeList(diffContext.getChangedFiles()),
                List.of(),
                5
        );
        ReleaseRiskReportEntity baselineReport = buildReport(task, diffContext, knowledgeMatches);
        CodeOpsReleaseRiskAgentOutput agentOutput = releaseRiskAgentService.analyze(CodeOpsReleaseRiskAgentInput.builder()
                .taskId(task.getTaskId())
                .taskType(task.getTaskType())
                .goal(task.getGoal())
                .repositoryPath(value(diffContext.getRepositoryPath()))
                .changeRef(value(diffContext.getChangeRef(), value(task.getChangeRef(), "working_tree")))
                .diffSummary(value(diffContext.getDiffSummary()))
                .changedFiles(safeList(diffContext.getChangedFiles()))
                .relatedTestFiles(safeList(diffContext.getRelatedTestFiles()))
                .opsEvidence(skillOutput(task, OpsDiagnosisEngineeringSkill.SKILL_ID))
                .fixStrategy(fixStrategy(task))
                .codeLocalization(codeLocalizationOutput(task))
                .patchGeneration(skillOutput(task, BugFixSkill.SKILL_ID))
                .testVerification(skillOutput(task, TestVerificationSkill.SKILL_ID))
                .reflectionFailures(extractReflectionFailures(task))
                .knowledgeMatches(knowledgeMatches)
                .baselineReport(baselineReport)
                .build());
        ReleaseRiskReportEntity report = agentOutput.getReport() == null ? baselineReport : agentOutput.getReport();
        String status = Boolean.TRUE.equals(diffContext.getDiffAvailable()) ? "SUCCESS" : "NO_DIFF";
        Map<String, Object> rawOutput = new LinkedHashMap<>();
        rawOutput.put("phase", "PHASE_6_LLM_RELEASE_RISK");
        rawOutput.put("diffAvailable", Boolean.TRUE.equals(diffContext.getDiffAvailable()));
        rawOutput.put("diffSummary", value(diffContext.getDiffSummary()));
        rawOutput.put("changedFiles", safeList(diffContext.getChangedFiles()));
        rawOutput.put("relatedTestFiles", safeList(diffContext.getRelatedTestFiles()));
        rawOutput.put("fixStrategy", fixStrategy(task));
        rawOutput.put("reflectionFailures", extractReflectionFailures(task));
        rawOutput.put("hunkCount", safeList(diffContext.getHunks()).size());
        rawOutput.put("knowledgeMatches", knowledgeMatches);
        rawOutput.put("releaseRiskReport", report);
        rawOutput.put("baselineReleaseRiskReport", baselineReport);
        rawOutput.put("llmReleaseRiskSuccess", agentOutput.isSuccess());
        rawOutput.put("llmReleaseRiskFallback", agentOutput.isFallback());
        rawOutput.put("releaseRiskReasoning", agentOutput.getReasoning() == null ? List.of() : agentOutput.getReasoning());
        rawOutput.put("humanApprovalPoints", agentOutput.getHumanApprovalPoints() == null ? List.of() : agentOutput.getHumanApprovalPoints());
        rawOutput.put("modelRouting", agentOutput.getModelRouting() == null ? Map.of() : agentOutput.getModelRouting());
        rawOutput.put("llmReleaseRiskError", value(agentOutput.getErrorMessage()));
        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status(status)
                .summary("发布风险分析完成：风险等级=" + report.getRiskLevel()
                        + "，影响范围=" + report.getImpactScopes().size()
                        + "，风险点=" + report.getRiskPoints().size()
                        + "，回归重点=" + report.getRegressionFocus().size())
                .evidence(buildEvidence(diffContext, report))
                .nextActions(List.of(
                        "根据风险点补齐或指定回归测试",
                        "发布前确认上线观察指标和告警阈值",
                        "发布前确认回滚方案、配置回退和数据兼容性"
                ))
                .rawOutput(rawOutput)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> skillOutput(EngineeringTaskEntity task, String skillId) {
        if (task.getContext() == null) {
            return Map.of();
        }
        Object skillOutputs = task.getContext().get("skillOutputs");
        if (!(skillOutputs instanceof Map<?, ?> outputs)) {
            return Map.of();
        }
        Object output = outputs.get(skillId);
        if (!(output instanceof Map<?, ?> outputMap)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        outputMap.forEach((key, value) -> values.put(String.valueOf(key), value));
        return values;
    }

    private Map<String, Object> fixStrategy(EngineeringTaskEntity task) {
        Map<String, Object> routerOutput = skillOutput(task, FixStrategyRouterSkill.SKILL_ID);
        if (!routerOutput.isEmpty()) {
            return routerOutput;
        }
        Map<String, Object> triageOutput = codeLocalizationOutput(task);
        if (triageOutput.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> strategy = new LinkedHashMap<>();
        putIfPresent(strategy, "strategyType", triageOutput.get("strategyType"));
        putIfPresent(strategy, "shouldEnterCodeRepair", triageOutput.get("shouldEnterCodeRepair"));
        putIfPresent(strategy, "confidence", triageOutput.get("localizationConfidence"));
        putIfPresent(strategy, "reasoning", triageOutput.get("localizationReasoning"));
        putIfPresent(strategy, "missingEvidence", triageOutput.get("missingEvidence"));
        return strategy;
    }

    private Map<String, Object> codeLocalizationOutput(EngineeringTaskEntity task) {
        Map<String, Object> repoOutput = skillOutput(task, RepoUnderstandingSkill.SKILL_ID);
        if (!repoOutput.isEmpty()) {
            return repoOutput;
        }
        return skillOutput(task, AgentLoopEngineeringSkill.SKILL_ID);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private List<Object> extractReflectionFailures(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return List.of();
        }
        Object failures = task.getContext().get("incidentFixReflectionFailures");
        if (failures instanceof List<?> values) {
            return new ArrayList<>(values);
        }
        return List.of();
    }

    private ReleaseRiskReportEntity buildReport(EngineeringTaskEntity task,
                                                RepoDiffContextEntity diffContext,
                                                List<EngineeringKnowledgeMatchEntity> knowledgeMatches) {
        List<String> changedFiles = safeList(diffContext.getChangedFiles());
        List<RepoDiffHunkEntity> hunks = safeList(diffContext.getHunks());
        List<String> riskPoints = buildRiskPoints(changedFiles, hunks, diffContext);
        List<String> regressionFocus = buildRegressionFocus(changedFiles, diffContext, riskPoints);
        List<String> observationMetrics = buildObservationMetrics(changedFiles, hunks);
        List<String> rollbackFocus = buildRollbackFocus(changedFiles, hunks, riskPoints);
        List<String> knowledgeReferences = buildKnowledgeReferences(knowledgeMatches);
        return ReleaseRiskReportEntity.builder()
                .repositoryPath(value(diffContext.getRepositoryPath()))
                .changeRef(value(diffContext.getChangeRef(), value(task.getChangeRef(), "working_tree")))
                .riskLevel(resolveRiskLevel(changedFiles, riskPoints))
                .impactScopes(buildImpactScopes(changedFiles, hunks))
                .riskPoints(riskPoints)
                .regressionFocus(regressionFocus)
                .onlineObservationMetrics(observationMetrics)
                .rollbackFocus(rollbackFocus)
                .knowledgeReferences(knowledgeReferences)
                .build();
    }

    private List<String> buildImpactScopes(List<String> changedFiles, List<RepoDiffHunkEntity> hunks) {
        Set<String> scopes = new LinkedHashSet<>();
        for (String file : changedFiles) {
            String lower = file.toLowerCase(Locale.ROOT);
            if (lower.contains("/controller/") || lower.contains("\\controller\\")) {
                scopes.add("接口入口层：变更涉及 Controller，需要关注请求参数、鉴权、响应码和兼容性。");
            }
            if (lower.contains("/service/") || lower.contains("\\service\\")) {
                scopes.add("业务服务层：变更涉及 Service，需要关注核心业务分支、事务边界和异常处理。");
            }
            if (lower.contains("/repository/") || lower.contains("/mapper/")
                    || lower.contains("\\repository\\") || lower.contains("\\mapper\\")) {
                scopes.add("数据访问层：变更涉及 Repository/Mapper，需要关注 SQL、索引、分页和数据兼容性。");
            }
            if (lower.contains("/config/") || lower.endsWith(".yml") || lower.endsWith(".yaml")
                    || lower.endsWith(".properties")) {
                scopes.add("配置层：变更涉及配置项，需要关注环境差异、默认值和灰度回退。");
            }
            if (lower.contains("pom.xml") || lower.contains("build.gradle")) {
                scopes.add("构建依赖层：变更涉及依赖或构建配置，需要关注依赖冲突、镜像构建和启动兼容性。");
            }
            if (lower.contains("mq") || lower.contains("kafka") || lower.contains("rocketmq")) {
                scopes.add("异步消息链路：变更涉及消息生产/消费，需要关注幂等、重复消费和积压。");
            }
            if (lower.contains("cache") || lower.contains("redis")) {
                scopes.add("缓存链路：变更涉及缓存，需要关注缓存一致性、过期时间和击穿风险。");
            }
        }
        if (containsAddedText(hunks, "http", "feign", "resttemplate", "webclient", "dubbo")) {
            scopes.add("外部依赖调用：新增或修改远程调用，需要关注超时、降级和重试边界。");
        }
        if (scopes.isEmpty()) {
            scopes.add(changedFiles.isEmpty() ? "未读取到变更文件，暂无法判断影响范围。" : "通用代码变更：需要结合变更文件和业务入口确认实际影响面。");
        }
        return new ArrayList<>(scopes);
    }

    private List<String> buildRiskPoints(List<String> changedFiles,
                                         List<RepoDiffHunkEntity> hunks,
                                         RepoDiffContextEntity diffContext) {
        Set<String> risks = new LinkedHashSet<>();
        if (!Boolean.TRUE.equals(diffContext.getDiffAvailable())) {
            risks.add("未读取到 diff，发布风险只能给出骨架判断，无法定位具体变更。");
        }
        if (changedFiles.size() >= 12) {
            risks.add("变更文件数量较多，建议拆分发布或提高回归范围，避免影响面过大。");
        }
        if (safeList(diffContext.getRelatedTestFiles()).isEmpty()
                && changedFiles.stream().anyMatch(file -> file.endsWith(".java") && !file.contains("/src/test/"))) {
            risks.add("Java 业务代码有变更但未识别到相关测试文件，存在回归覆盖不足风险。");
        }
        addRiskIf(risks, hunks,
                "事务相关代码发生变化，需要关注事务边界、异常回滚和跨服务调用混在事务内的问题。",
                "transaction", "@transactional", "rollbackfor");
        addRiskIf(risks, hunks,
                "缓存相关代码发生变化，需要关注缓存一致性、TTL、穿透/击穿以及发布后的脏缓存处理。",
                "redis", "cache", "caffeine");
        addRiskIf(risks, hunks,
                "并发或异步执行逻辑发生变化，需要关注线程池容量、上下文传递、异常吞掉和任务堆积。",
                "threadpool", "executor", "async", "completablefuture");
        addRiskIf(risks, hunks,
                "外部依赖调用发生变化，需要关注超时、重试、熔断降级和下游错误码兼容。",
                "resttemplate", "webclient", "feign", "dubbo", "httpclient");
        addRiskIf(risks, hunks,
                "数据访问逻辑发生变化，需要关注慢 SQL、索引命中、分页边界和数据一致性。",
                "insert", "update", "delete", "select", "mapper");
        addRiskIf(risks, hunks,
                "异常处理逻辑发生变化，需要关注异常被吞、错误码不准确和问题不可观测。",
                "catch (exception", "catch (throwable", "return null");
        addRiskIf(risks, hunks,
                "新增代码仍包含 TODO/FIXME，发布前需要确认是否为未闭环逻辑。",
                "todo", "fixme");
        for (String file : changedFiles) {
            String lower = file.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties")) {
                risks.add("配置文件发生变化，需要确认多环境配置、默认值、密钥脱敏和回滚方式。");
            }
            if (lower.contains("migration") || lower.contains("schema") || lower.contains(".sql")) {
                risks.add("数据库脚本或 schema 发生变化，需要确认向前/向后兼容、执行顺序和回滚脚本。");
            }
        }
        return limit(new ArrayList<>(risks), MAX_RISK_POINTS);
    }

    private List<String> buildRegressionFocus(List<String> changedFiles,
                                              RepoDiffContextEntity diffContext,
                                              List<String> riskPoints) {
        Set<String> focus = new LinkedHashSet<>();
        focus.add("覆盖本次变更直接影响的接口、核心分支和失败分支。");
        if (!safeList(diffContext.getRelatedTestFiles()).isEmpty()) {
            focus.add("优先执行相关测试：" + String.join(", ", safeList(diffContext.getRelatedTestFiles())));
        }
        if (riskPoints.stream().anyMatch(point -> point.contains("事务"))) {
            focus.add("补充事务回滚、部分失败和重复请求场景。");
        }
        if (riskPoints.stream().anyMatch(point -> point.contains("缓存"))) {
            focus.add("补充缓存命中、缓存未命中、过期和脏数据刷新场景。");
        }
        if (riskPoints.stream().anyMatch(point -> point.contains("外部依赖"))) {
            focus.add("补充下游超时、下游 5xx、返回字段缺失和降级兜底场景。");
        }
        if (riskPoints.stream().anyMatch(point -> point.contains("数据访问"))) {
            focus.add("补充空结果、大分页、边界条件和慢查询 explain 验证。");
        }
        if (changedFiles.stream().anyMatch(file -> file.toLowerCase(Locale.ROOT).contains("controller"))) {
            focus.add("补充接口兼容性、参数校验、鉴权和错误码回归。");
        }
        return new ArrayList<>(focus);
    }

    private List<String> buildObservationMetrics(List<String> changedFiles, List<RepoDiffHunkEntity> hunks) {
        Set<String> metrics = new LinkedHashSet<>();
        metrics.add("接口 5xx 数量、错误率和核心接口 P95/P99 延迟。");
        metrics.add("服务实例 CPU、内存、GC、线程池队列和重启次数。");
        if (containsAddedText(hunks, "select", "insert", "update", "delete", "mapper")) {
            metrics.add("数据库慢 SQL、连接池 active/pending/timeout、事务耗时。");
        }
        if (containsAddedText(hunks, "redis", "cache")) {
            metrics.add("缓存命中率、Redis 慢命令、热点 key、缓存错误数。");
        }
        if (containsAddedText(hunks, "feign", "resttemplate", "webclient", "dubbo", "httpclient")) {
            metrics.add("下游调用成功率、超时数、重试次数、熔断/降级次数。");
        }
        if (changedFiles.stream().anyMatch(file -> file.toLowerCase(Locale.ROOT).contains("mq")
                || file.toLowerCase(Locale.ROOT).contains("kafka")
                || file.toLowerCase(Locale.ROOT).contains("rocketmq"))) {
            metrics.add("消息积压、消费失败率、重复消费和死信队列数量。");
        }
        return new ArrayList<>(metrics);
    }

    private List<String> buildRollbackFocus(List<String> changedFiles,
                                            List<RepoDiffHunkEntity> hunks,
                                            List<String> riskPoints) {
        Set<String> rollback = new LinkedHashSet<>();
        rollback.add("确认应用版本可快速回退，且回退后配置与依赖版本兼容。");
        if (changedFiles.stream().anyMatch(file -> file.endsWith(".yml") || file.endsWith(".yaml") || file.endsWith(".properties"))) {
            rollback.add("配置变更需要准备独立回退项，避免仅回滚代码但配置仍保留新值。");
        }
        if (changedFiles.stream().anyMatch(file -> file.toLowerCase(Locale.ROOT).contains(".sql")
                || file.toLowerCase(Locale.ROOT).contains("migration"))) {
            rollback.add("数据库变更需要确认是否可逆，优先采用兼容性发布，避免直接依赖回滚 DDL。");
        }
        if (containsAddedText(hunks, "redis", "cache") || riskPoints.stream().anyMatch(point -> point.contains("缓存"))) {
            rollback.add("缓存逻辑变更需要准备缓存清理、预热或 key 版本切换方案。");
        }
        if (containsAddedText(hunks, "mq", "kafka", "rocketmq")) {
            rollback.add("消息链路变更需要确认消费位点、重复消费和消息格式兼容。");
        }
        return new ArrayList<>(rollback);
    }

    private List<String> buildKnowledgeReferences(List<EngineeringKnowledgeMatchEntity> matches) {
        List<String> references = new ArrayList<>();
        for (EngineeringKnowledgeMatchEntity match : safeList(matches)) {
            references.add("[" + value(match.getCategory()) + "][" + value(match.getScore()) + "] "
                    + value(match.getTitle()) + " -> " + value(match.getPath()));
        }
        if (references.isEmpty()) {
            references.add("未命中发布规范、复盘或 Runbook 文档；建议补充工程知识库后复评。");
        }
        return references;
    }

    private List<String> buildEvidence(RepoDiffContextEntity diffContext, ReleaseRiskReportEntity report) {
        List<String> evidence = new ArrayList<>();
        evidence.add("仓库：" + value(report.getRepositoryPath()));
        evidence.add("变更引用：" + value(report.getChangeRef(), "working_tree"));
        evidence.add("Diff 摘要：" + value(diffContext.getDiffSummary()));
        evidence.add("影响范围：" + String.join("；", report.getImpactScopes()));
        evidence.add("风险点：" + String.join("；", report.getRiskPoints()));
        evidence.add("回归重点：" + String.join("；", report.getRegressionFocus()));
        evidence.add("上线观察指标：" + String.join("；", report.getOnlineObservationMetrics()));
        evidence.add("回滚关注点：" + String.join("；", report.getRollbackFocus()));
        evidence.add("知识引用：" + String.join("；", report.getKnowledgeReferences()));
        return evidence;
    }

    private String resolveRiskLevel(List<String> changedFiles, List<String> riskPoints) {
        int score = riskPoints.size();
        if (changedFiles.size() >= 12) {
            score += 2;
        }
        if (riskPoints.stream().anyMatch(point -> point.contains("数据库") || point.contains("事务") || point.contains("外部依赖"))) {
            score += 2;
        }
        if (score >= 8) {
            return "HIGH";
        }
        if (score >= 4) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private void addRiskIf(Set<String> risks, List<RepoDiffHunkEntity> hunks, String risk, String... tokens) {
        if (containsAddedText(hunks, tokens)) {
            risks.add(risk);
        }
    }

    private boolean containsAddedText(List<RepoDiffHunkEntity> hunks, String... tokens) {
        for (RepoDiffHunkEntity hunk : safeList(hunks)) {
            String text = String.join("\n", safeList(hunk.getAddedLines())).toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (text.contains(token.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private <T> List<T> limit(List<T> values, int max) {
        if (values == null || values.size() <= max) {
            return values == null ? List.of() : values;
        }
        return new ArrayList<>(values.subList(0, max));
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String value(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

}
