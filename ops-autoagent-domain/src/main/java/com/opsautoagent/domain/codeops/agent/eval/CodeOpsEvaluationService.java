package com.opsautoagent.domain.codeops.agent.eval;

import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskStepEntity;
import com.opsautoagent.domain.codeops.agent.memory.IncidentMemoryService;
import com.opsautoagent.domain.codeops.service.EngineeringTaskAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class CodeOpsEvaluationService {

    private final EngineeringTaskAgentService engineeringTaskAgentService;
    private final CodeOpsEvalReportBuilder reportBuilder;
    private final CodeOpsReportMarkdownGenerator markdownGenerator;
    private final IncidentMemoryService incidentMemoryService;

    public CodeOpsEvaluationService(EngineeringTaskAgentService engineeringTaskAgentService,
                                     CodeOpsEvalReportBuilder reportBuilder,
                                     CodeOpsReportMarkdownGenerator markdownGenerator,
                                     IncidentMemoryService incidentMemoryService) {
        this.engineeringTaskAgentService = engineeringTaskAgentService;
        this.reportBuilder = reportBuilder;
        this.markdownGenerator = markdownGenerator;
        this.incidentMemoryService = incidentMemoryService;
    }

    public CodeOpsEvalSummary runBuiltinCases() {
        String batchId = "codeops-eval-batch-" + UUID.randomUUID();
        List<CodeOpsEvalRun> runs = new ArrayList<>();
        for (CodeOpsEvalCase evalCase : builtinCases()) {
            runs.add(runCase(batchId, evalCase));
        }
        return summarize(batchId, runs);
    }

    public CodeOpsEvalSummary runBuiltinCase(String caseId) {
        for (CodeOpsEvalCase evalCase : builtinCases()) {
            if (evalCase.getCaseId().equalsIgnoreCase(caseId)) {
                String batchId = "codeops-eval-batch-" + UUID.randomUUID();
                return summarize(batchId, List.of(runCase(batchId, evalCase)));
            }
        }
        throw new IllegalArgumentException("CodeOps builtin eval case not found: " + caseId);
    }

    private CodeOpsEvalRun runCase(String batchId, CodeOpsEvalCase evalCase) {
        String runId = "codeops-eval-run-" + UUID.randomUUID();
        long start = System.currentTimeMillis();

        // Sandbox: restore sample repository to clean baseline before each case
        restoreSampleBaseline(evalCase.getRepository());

        try {
            EngineeringTaskEntity task = engineeringTaskAgentService.submit(EngineeringTaskEntity.builder()
                    .taskType(evalCase.getTaskType())
                    .goal(evalCase.getGoal())
                    .repository(evalCase.getRepository())
                    .changeRef(evalCase.getChangeRef())
                    .focusAreas(evalCase.getFocusAreas())
                    .context(evalCase.getContext())
                    .maxRounds(8)
                    .maxToolCalls(40)
                    .build());
            long latencyMs = System.currentTimeMillis() - start;
            return score(batchId, runId, evalCase, task, latencyMs, null);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("CodeOps eval case failed. caseId={}", evalCase.getCaseId(), e);
            return score(batchId, runId, evalCase, null, latencyMs, e.getMessage());
        }
    }

    private CodeOpsEvalRun score(String batchId,
                                 String runId,
                                 CodeOpsEvalCase evalCase,
                                 EngineeringTaskEntity task,
                                 long latencyMs,
                                 String errorMessage) {
        List<EngineeringTaskStepEntity> steps = task == null || task.getSteps() == null ? List.of() : task.getSteps();
        List<String> selectedSkills = steps.stream()
                .map(EngineeringTaskStepEntity::getSelectedSkill)
                .filter(skill -> skill != null && !skill.trim().isEmpty())
                .toList();
        String taskText = normalize(buildTaskText(task));

        List<String> missingSkills = missing(evalCase.getExpectedSkills(), selectedSkills);
        List<String> missingEvidence = missingByText(evalCase.getExpectedEvidenceKeywords(), taskText);
        List<String> missingArtifacts = missingByText(evalCase.getExpectedArtifacts(), taskText);
        List<String> missingTargetFiles = missingByText(evalCase.getExpectedTargetFiles(), taskText);
        List<String> missingTargetMethods = missingByText(evalCase.getExpectedTargetMethods(), taskText);
        List<String> missingPatchKeywords = missingByText(evalCase.getExpectedPatchKeywords(), taskText);
        List<String> missingTestNames = missingByText(evalCase.getExpectedTestNames(), taskText);
        List<String> missingRiskKeywords = missingByText(evalCase.getExpectedRiskKeywords(), taskText);
        List<String> hitTargetFiles = hitByMissing(evalCase.getExpectedTargetFiles(), missingTargetFiles);
        List<String> hitTargetMethods = hitByMissing(evalCase.getExpectedTargetMethods(), missingTargetMethods);
        List<String> hitPatchKeywords = hitByMissing(evalCase.getExpectedPatchKeywords(), missingPatchKeywords);
        List<String> hitTestNames = hitByMissing(evalCase.getExpectedTestNames(), missingTestNames);
        List<String> hitRiskKeywords = hitByMissing(evalCase.getExpectedRiskKeywords(), missingRiskKeywords);

        BigDecimal skillCoverage = coverage(evalCase.getExpectedSkills().size(), missingSkills.size());
        BigDecimal evidenceCoverage = coverage(evalCase.getExpectedEvidenceKeywords().size(), missingEvidence.size());
        BigDecimal artifactCoverage = coverage(evalCase.getExpectedArtifacts().size(), missingArtifacts.size());
        BigDecimal codeLocalizationCoverage = coverage(size(evalCase.getExpectedTargetFiles()) + size(evalCase.getExpectedTargetMethods()),
                missingTargetFiles.size() + missingTargetMethods.size());
        BigDecimal patchCoverage = coverage(size(evalCase.getExpectedPatchKeywords()), missingPatchKeywords.size());
        BigDecimal testCoverage = coverage(size(evalCase.getExpectedTestNames()), missingTestNames.size());
        BigDecimal riskCoverage = coverage(size(evalCase.getExpectedRiskKeywords()), missingRiskKeywords.size());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchId", batchId);
        detail.put("caseName", evalCase.getCaseName());
        detail.put("selectedSkills", selectedSkills);
        detail.put("expectedSkills", evalCase.getExpectedSkills());
        detail.put("expectedEvidenceKeywords", evalCase.getExpectedEvidenceKeywords());
        detail.put("expectedArtifacts", evalCase.getExpectedArtifacts());
        detail.put("metricDefinitions", metricDefinitions());
        detail.put("codeLocalizationCoverage", metricDetail(
                codeLocalizationCoverage,
                concat(evalCase.getExpectedTargetFiles(), evalCase.getExpectedTargetMethods()),
                concat(hitTargetFiles, hitTargetMethods),
                concat(missingTargetFiles, missingTargetMethods),
                "衡量 Agent 是否把线上故障定位到预期代码位置，分母是 expectedTargetFiles + expectedTargetMethods，命中项必须出现在任务最终摘要、步骤摘要或证据 JSON 中。"));
        detail.put("patchCoverage", metricDetail(
                patchCoverage,
                evalCase.getExpectedPatchKeywords(),
                hitPatchKeywords,
                missingPatchKeywords,
                "衡量 Patch Generation Agent 的修复 diff/修复说明是否覆盖关键修改意图，分母是 expectedPatchKeywords。"));
        detail.put("testCoverage", metricDetail(
                testCoverage,
                evalCase.getExpectedTestNames(),
                hitTestNames,
                missingTestNames,
                "衡量 Test Verification Agent 是否选择或执行了预期相关测试，分母是 expectedTestNames。"));
        detail.put("riskCoverage", metricDetail(
                riskCoverage,
                evalCase.getExpectedRiskKeywords(),
                hitRiskKeywords,
                missingRiskKeywords,
                "衡量 Release Risk Agent 是否覆盖上线观察、回滚、故障指标等风险要点，分母是 expectedRiskKeywords。"));
        detail.put("legacyCoverageScores", Map.of(
                "codeLocalizationCoverage", codeLocalizationCoverage,
                "patchCoverage", patchCoverage,
                "testCoverage", testCoverage,
                "riskCoverage", riskCoverage));
        detail.put("taskStatus", task == null ? "" : task.getStatus());
        detail.put("hasFailedRepairOrTestStep", hasFailedRepairOrTestStep(steps));
        detail.put("finalSummary", task == null ? "" : task.getFinalSummary());

        boolean success = errorMessage == null
                && task != null
                && isSuccessfulTaskStatus(task.getStatus())
                && !hasFailedRepairOrTestStep(steps)
                && skillCoverage.compareTo(BigDecimal.ONE) == 0
                && evidenceCoverage.compareTo(BigDecimal.valueOf(0.5)) >= 0
                && artifactCoverage.compareTo(BigDecimal.valueOf(0.5)) >= 0
                && codeLocalizationCoverage.compareTo(BigDecimal.valueOf(0.5)) >= 0
                && patchCoverage.compareTo(BigDecimal.valueOf(0.5)) >= 0
                && testCoverage.compareTo(BigDecimal.valueOf(0.5)) >= 0
                && riskCoverage.compareTo(BigDecimal.valueOf(0.5)) >= 0;

        // Store successful fix pattern to incident memory for future recall
        if (success && evalCase != null && task != null) {
            try {
                MemoryExtraction mem = extractMemoryFromTask(task, evalCase);
                incidentMemoryService.storeSuccess(
                        evalCase.getCaseId(), evalCase.getCaseName(),
                        mem.scopeType, mem.targetMethods,
                        mem.rootCause, mem.fixStrategy, mem.riskLevel);
            } catch (Exception ignored) {
                log.debug("Failed to store incident memory: {}", ignored.getMessage());
            }
        }

        return CodeOpsEvalRun.builder()
                .runId(runId)
                .caseId(evalCase.getCaseId())
                .taskId(task == null ? null : task.getTaskId())
                .taskType(evalCase.getTaskType())
                .status(success ? "SUCCESS" : "FAILED")
                .expectedSkillCoverage(skillCoverage)
                .evidenceKeywordCoverage(evidenceCoverage)
                .artifactCoverage(artifactCoverage)
                .stepCount(steps.size())
                .usedToolCalls(task == null ? 0 : task.getUsedToolCalls())
                .latencyMs(latencyMs)
                .missingSkills(missingSkills)
                .missingEvidenceKeywords(missingEvidence)
                .missingArtifacts(missingArtifacts)
                .detail(detail)
                .errorMessage(errorMessage)
                .build();
    }

    private boolean hasFailedRepairOrTestStep(List<EngineeringTaskStepEntity> steps) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        return lastStepFailed(steps, "bug_fix") || lastStepFailed(steps, "test_verification");
    }

    private boolean isSuccessfulTaskStatus(String status) {
        return "COMPLETED".equals(status) || "WAITING_APPROVAL".equals(status);
    }

    private boolean lastStepFailed(List<EngineeringTaskStepEntity> steps, String skillId) {
        return steps.stream()
                .filter(step -> skillId.equals(step.getSelectedSkill()))
                .reduce((first, second) -> second)
                .map(step -> "FAILED".equals(step.getStatus()))
                .orElse(false);
    }

    private CodeOpsEvalReport lastReport;

    /**
     * Restore sample repository files to clean git baseline before each eval case.
     * This ensures consecutive cases don't interfere — each case starts from pristine code.
     */
    private void restoreSampleBaseline(String repository) {
        if (repository == null || repository.isBlank()) return;
        try {
            java.nio.file.Path repoPath = java.nio.file.Path.of(repository).toAbsolutePath().normalize();
            if (!java.nio.file.Files.exists(repoPath)) return;

            ProcessBuilder pb = new ProcessBuilder("git", "checkout", "--", ".");
            pb.directory(repoPath.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();

            // Also clean up generated test files (created by LLM in previous runs)
            java.nio.file.Path testDir = repoPath.resolve("src/test/java/com/example/order");
            if (java.nio.file.Files.exists(testDir)) {
                try (var paths = java.nio.file.Files.list(testDir)) {
                    paths.filter(f -> {
                        String name = f.getFileName().toString();
                        return name.contains("Concurrency") || name.contains("ServiceTest")
                                || name.endsWith("NpeTest.java") || name.endsWith("GuardTest.java");
                    }).forEach(f -> {
                        try { java.nio.file.Files.deleteIfExists(f); } catch (Exception ignored) {}
                    });
                }
            }
        } catch (Exception e) {
            log.debug("Baseline restore skipped for {}: {}", repository, e.getMessage());
        }
    }

    public CodeOpsEvalReport getLastReport() {
        return lastReport;
    }

    public CodeOpsEvalReport generateFullReport(String batchId, List<CodeOpsEvalRun> runs) {
        List<CodeOpsEvalCaseReport> caseReports = new ArrayList<>();
        for (CodeOpsEvalRun run : runs) {
            CodeOpsEvalCase evalCase = findCase(run.getCaseId());
            EngineeringTaskEntity task = engineeringTaskAgentService.query(run.getTaskId());
            caseReports.add(reportBuilder.buildCaseReport(batchId, evalCase, run, task));
        }
        CodeOpsEvalReport report = reportBuilder.buildReport(batchId, caseReports);
        persistReport(report);
        lastReport = report;
        return report;
    }

    private void persistReport(CodeOpsEvalReport report) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("data/codeops-eval/" + report.getBatchId());
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("report.json"),
                    com.alibaba.fastjson.JSON.toJSONString(report));
            java.nio.file.Files.writeString(dir.resolve("report.md"),
                    markdownGenerator.generateSummaryReport(report));

            java.nio.file.Path casesDir = dir.resolve("cases");
            java.nio.file.Files.createDirectories(casesDir);
            for (CodeOpsEvalCaseReport c : report.getCases()) {
                java.nio.file.Files.writeString(casesDir.resolve(c.getCaseId() + ".json"),
                        com.alibaba.fastjson.JSON.toJSONString(c));
                java.nio.file.Files.writeString(casesDir.resolve(c.getCaseId() + ".md"),
                        markdownGenerator.generateCaseReport(c));
            }
        } catch (java.io.IOException e) {
            log.warn("Failed to persist eval report: {}", e.getMessage());
        }
    }

    private CodeOpsEvalCase findCase(String caseId) {
        for (CodeOpsEvalCase c : builtinCases()) {
            if (c.getCaseId().equals(caseId)) return c;
        }
        return null;
    }

    private CodeOpsEvalSummary summarize(String batchId, List<CodeOpsEvalRun> runs) {
        // Generate full report
        CodeOpsEvalReport report = generateFullReport(batchId, runs);
        BigDecimal total = BigDecimal.valueOf(Math.max(1, runs.size()));
        int success = 0;
        BigDecimal skillCoverage = BigDecimal.ZERO;
        BigDecimal evidenceCoverage = BigDecimal.ZERO;
        BigDecimal artifactCoverage = BigDecimal.ZERO;
        BigDecimal codeLocalizationCoverage = BigDecimal.ZERO;
        BigDecimal patchCoverage = BigDecimal.ZERO;
        BigDecimal testCoverage = BigDecimal.ZERO;
        BigDecimal riskCoverage = BigDecimal.ZERO;
        BigDecimal stepCount = BigDecimal.ZERO;
        BigDecimal toolCalls = BigDecimal.ZERO;
        BigDecimal latency = BigDecimal.ZERO;
        for (CodeOpsEvalRun run : runs) {
            if ("SUCCESS".equals(run.getStatus())) {
                success++;
            }
            skillCoverage = skillCoverage.add(run.getExpectedSkillCoverage());
            evidenceCoverage = evidenceCoverage.add(run.getEvidenceKeywordCoverage());
            artifactCoverage = artifactCoverage.add(run.getArtifactCoverage());
            codeLocalizationCoverage = codeLocalizationCoverage.add(extractDetailScore(run, "codeLocalizationCoverage"));
            patchCoverage = patchCoverage.add(extractDetailScore(run, "patchCoverage"));
            testCoverage = testCoverage.add(extractDetailScore(run, "testCoverage"));
            riskCoverage = riskCoverage.add(extractDetailScore(run, "riskCoverage"));
            stepCount = stepCount.add(BigDecimal.valueOf(run.getStepCount()));
            toolCalls = toolCalls.add(BigDecimal.valueOf(run.getUsedToolCalls()));
            latency = latency.add(BigDecimal.valueOf(run.getLatencyMs()));
        }
        return CodeOpsEvalSummary.builder()
                .batchId(batchId)
                .totalCases(runs.size())
                .successCases(success)
                .failedCases(runs.size() - success)
                .averageExpectedSkillCoverage(skillCoverage.divide(total, 4, RoundingMode.HALF_UP))
                .averageEvidenceKeywordCoverage(evidenceCoverage.divide(total, 4, RoundingMode.HALF_UP))
                .averageArtifactCoverage(artifactCoverage.divide(total, 4, RoundingMode.HALF_UP))
                .averageCodeLocalizationCoverage(codeLocalizationCoverage.divide(total, 4, RoundingMode.HALF_UP))
                .averagePatchCoverage(patchCoverage.divide(total, 4, RoundingMode.HALF_UP))
                .averageTestCoverage(testCoverage.divide(total, 4, RoundingMode.HALF_UP))
                .averageRiskCoverage(riskCoverage.divide(total, 4, RoundingMode.HALF_UP))
                .averageStepCount(stepCount.divide(total, 4, RoundingMode.HALF_UP))
                .averageToolCallCount(toolCalls.divide(total, 4, RoundingMode.HALF_UP))
                .averageLatencyMs(latency.divide(total, 4, RoundingMode.HALF_UP))
                .reportJsonPath("data/codeops-eval/" + batchId + "/report.json")
                .reportMarkdownPath("data/codeops-eval/" + batchId + "/report.md")
                .runs(runs)
                .build();
    }

    private List<CodeOpsEvalCase> builtinCases() {
        return List.of(
                CodeOpsEvalCase.builder()
                        .caseId("code-review-basic")
                        .caseName("当前 diff 代码审查")
                        .taskType("CODE_REVIEW")
                        .goal("Review 当前工作区 diff，重点检查事务边界、缓存一致性、外部依赖失败处理和缺失测试。")
                        .focusAreas(List.of("transaction", "cache", "dependency_failure", "test"))
                        .expectedSkills(List.of("repo_understanding", "engineering_knowledge_rag", "pr_review", "test_verification"))
                        .expectedEvidenceKeywords(List.of("变更", "相关测试", "Review", "风险", "测试"))
                        .expectedArtifacts(List.of("findings", "recommendedTests"))
                        .build(),
                CodeOpsEvalCase.builder()
                        .caseId("issue-to-patch-basic")
                        .caseName("Issue 到修复建议")
                        .taskType("ISSUE_TO_PATCH")
                        .goal("分页查询接口在 keyword 为空时结果不正确，请定位并给出修复方案和测试建议。")
                        .focusAreas(List.of("bug_fix", "test"))
                        .expectedSkills(List.of("repo_understanding", "engineering_knowledge_rag", "bug_fix", "test_verification"))
                        .expectedEvidenceKeywords(List.of("修复", "可疑位置", "测试", "Maven"))
                        .expectedArtifacts(List.of("patchDraft", "mavenCommands"))
                        .build(),
                CodeOpsEvalCase.builder()
                        .caseId("release-risk-basic")
                        .caseName("发布风险分析")
                        .taskType("RELEASE_RISK")
                        .goal("本次发布修改了订单提交和支付回调逻辑，请生成发布风险报告。")
                        .focusAreas(List.of("release", "rollback", "observability"))
                        .expectedSkills(List.of("repo_understanding", "engineering_knowledge_rag", "release_risk_analysis", "test_verification"))
                        .expectedEvidenceKeywords(List.of("发布", "风险", "回归", "观察", "回滚"))
                        .expectedArtifacts(List.of("riskPoints", "rollbackConcerns", "observationMetrics"))
                        .build(),
                CodeOpsEvalCase.builder()
                        .caseId("incident-to-fix-basic")
                        .caseName("order-service 5xx Incident-to-Fix")
                        .taskType("INCIDENT_TO_FIX")
                        .goal("order-service 近 5 分钟 POST /orders/submit 5xx 异常升高，请结合线上证据和代码上下文定位风险点。")
                        .repository("samples/order-service")
                        .focusAreas(List.of("incident", "bug_fix", "release_risk"))
                        .context(Map.of(
                                "serviceName", "order-service",
                                "endpoint", "POST /orders/submit",
                                "fixtureCase", "fixtures/incident/order-submit-5xx/eval-case.json",
                                "traceId", "trace-order-submit-001",
                                "codeHints", List.of("OrderSubmitService", "OrderController", "unitPrice", "NullPointerException")
                        ))
                        .expectedSkills(List.of("ops_diagnosis", "repo_understanding", "engineering_knowledge_rag", "bug_fix", "test_verification", "release_risk_analysis"))
                        .expectedEvidenceKeywords(List.of("诊断", "服务", "代码定位", "修复", "测试", "发布", "OrderSubmitService"))
                        .expectedArtifacts(List.of("opsDiagnosis", "patchDraft", "mavenCommands", "riskPoints"))
                        .expectedTargetFiles(List.of("src/main/java/com/example/order/OrderSubmitService.java"))
                        .expectedTargetMethods(List.of("submit"))
                        .expectedPatchKeywords(List.of("unitPrice", "quantity"))
                        .expectedTestNames(List.of("OrderSubmitServiceTest"))
                        .expectedRiskKeywords(List.of("5xx", "回滚", "观察"))
                        .build(),
                CodeOpsEvalCase.builder()
                        .caseId("incident-inventory-oversell-concurrency")
                        .caseName("order-service 库存超卖并发事故 Incident-to-Fix")
                        .taskType("INCIDENT_TO_FIX")
                        .goal("order-service 秒杀下单接口 POST /api/orders/submit 出现库存负数、重复 requestId 被处理多次、5xx 和冲突错误升高。请结合线上告警、日志、Trace 和代码上下文完成诊断、修复、回归测试和发布观察项。")
                        .repository("samples/order-service")
                        .focusAreas(List.of("incident", "concurrency", "idempotency", "bug_fix", "test_verification", "release_risk"))
                        .context(Map.of(
                                "serviceName", "order-service",
                                "endpoint", "POST /api/orders/submit",
                                "fixtureCase", "fixtures/incident/inventory-oversell-concurrency/eval-case.json",
                                "allowPatchApply", true,
                                "allowTestPatchApply", true
                        ))
                        .expectedSkills(List.of("ops_diagnosis", "repo_understanding", "engineering_knowledge_rag", "bug_fix", "test_verification", "release_risk_analysis"))
                        .expectedEvidenceKeywords(List.of("库存", "并发", "幂等", "requestId", "negative", "duplicate", "InventoryService"))
                        .expectedArtifacts(List.of("patchDraft", "mavenCommands", "riskPoints"))
                        .expectedTargetFiles(List.of(
                                "src/main/java/com/example/order/InventoryService.java",
                                "src/main/java/com/example/order/InventoryRepository.java",
                                "src/main/java/com/example/order/IdempotencyService.java",
                                "src/main/java/com/example/order/OrderSubmitService.java"
                        ))
                        .expectedTargetMethods(List.of("reserve", "submitFlashSale", "alreadyProcessed", "markProcessed"))
                        .expectedPatchKeywords(List.of("requestId", "ConcurrentHashMap", "synchronized", "atomic", "putIfAbsent", "stock"))
                        .expectedTestNames(List.of("InventoryConcurrencyTest", "OrderSubmitServiceConcurrencyTest"))
                        .expectedRiskKeywords(List.of("库存", "5xx", "冲突", "锁", "回滚", "观察"))
                        .build()
                ,
                CodeOpsEvalCase.builder()
                        .caseId("incident-db-pool-runtime-pressure")
                        .caseName("order-service DB 连接池耗尽非代码处置")
                        .taskType("INCIDENT_TO_FIX")
                        .goal("order-service 下单接口 POST /api/orders/submit 出现 5xx 升高，Hikari active 连接数达到 max，pending 线程和 connection timeout 快速上升。请结合线上证据判断是否需要代码修复，并给出处置与上线观察建议。")
                        .repository("samples/order-service")
                        .focusAreas(List.of("incident", "runtime", "config", "release_risk"))
                        .context(Map.of(
                                "serviceName", "order-service",
                                "endpoint", "POST /api/orders/submit",
                                "fixtureCase", "fixtures/incident/db-pool-runtime-pressure/eval-case.json"
                        ))
                        .expectedSkills(List.of("ops_diagnosis", "repo_understanding", "release_risk_analysis"))
                        .expectedEvidenceKeywords(List.of("Hikari", "pending", "timeout", "连接池"))
                        .expectedArtifacts(List.of("riskPoints"))
                        .expectedTargetFiles(List.of())
                        .expectedTargetMethods(List.of())
                        .expectedPatchKeywords(List.of())
                        .expectedTestNames(List.of())
                        .expectedRiskKeywords(List.of("Hikari", "连接池", "timeout", "回滚", "观察"))
                        .build()
                ,
                // === 新场景: NPE 代码修复 — 告警只描述现象，LLM 从 stack trace 自行诊断 ===
                CodeOpsEvalCase.builder()
                        .caseId("incident-order-create-npe")
                        .caseName("order-service 下单接口 NPE 代码修复")
                        .taskType("INCIDENT_TO_FIX")
                        .goal("order-service 下单接口 POST /api/orders/submit 5xx 错误率飙升至 8.2%。请结合线上告警、日志、Metrics 和 Trace 完成诊断，判断是否需要代码修复，并给出处置与上线观察建议。")
                        .repository("samples/order-service")
                        .focusAreas(List.of("incident", "bug_fix", "test_verification", "release_risk"))
                        .context(Map.of(
                                "serviceName", "order-service",
                                "endpoint", "POST /api/orders/submit",
                                "fixtureCase", "fixtures/incident/order-create-npe/eval-case.json",
                                "allowPatchApply", true,
                                "allowTestPatchApply", true
                        ))
                        .expectedSkills(List.of("ops_diagnosis", "repo_understanding", "engineering_knowledge_rag", "bug_fix", "test_verification", "release_risk_analysis"))
                        .expectedEvidenceKeywords(List.of("NullPointerException", "OrderSubmitService", "submit", "userId", "null"))
                        .expectedArtifacts(List.of("patchDraft", "mavenCommands", "riskPoints"))
                        .expectedTargetFiles(List.of("src/main/java/com/example/order/OrderSubmitService.java", "src/main/java/com/example/order/OrderRepository.java"))
                        .expectedTargetMethods(List.of("submit", "create"))
                        .expectedPatchKeywords(List.of("null", "userId", "IllegalArgumentException", "submit"))
                        .expectedTestNames(List.of("OrderSubmitService"))
                        .expectedRiskKeywords(List.of("NPE", "null", "回滚", "观察", "5xx"))
                        .build()
                ,
                // === 新场景: GC 延迟 — 不需要修代码，纯 JVM 运行时问题 ===
                CodeOpsEvalCase.builder()
                        .caseId("incident-gc-latency-spike")
                        .caseName("order-service GC 暂停延迟飙升非代码处置")
                        .taskType("INCIDENT_TO_FIX")
                        .goal("order-service P99 延迟超过 5 秒，持续 12 分钟，错误率正常 0.1%。请结合线上告警、日志、Metrics 和 Trace 完成诊断，判断是否需要代码修复，并给出处置与上线观察建议。")
                        .repository("samples/order-service")
                        .focusAreas(List.of("incident", "runtime", "config", "release_risk"))
                        .context(Map.of(
                                "serviceName", "order-service",
                                "endpoint", "POST /api/orders/submit",
                                "fixtureCase", "fixtures/incident/gc-latency-spike/eval-case.json"
                        ))
                        .expectedSkills(List.of("ops_diagnosis", "repo_understanding", "release_risk_analysis"))
                        .expectedEvidenceKeywords(List.of("GC", "heap", "pause", "延迟", "JVM"))
                        .expectedArtifacts(List.of("riskPoints"))
                        .expectedTargetFiles(List.of())
                        .expectedTargetMethods(List.of())
                        .expectedPatchKeywords(List.of())
                        .expectedTestNames(List.of())
                        .expectedRiskKeywords(List.of("GC", "heap", "JVM", "回滚", "观察"))
                        .build()
                ,
                // === Phase 3 验收: scope-violation-reflection ===
                CodeOpsEvalCase.builder()
                        .caseId("scope-violation-reflection")
                        .caseName("Scope Violation 反射修复 — Guard 拦截越界 patch 后 LLM 收敛")
                        .taskType("INCIDENT_TO_FIX")
                        .goal("order-service 下单接口 POST /api/orders/submit 5xx 错误率飙升至 8.2%。stack trace 指向 OrderSubmitService.submit 的 NullPointerException。请诊断并仅修复事故证据指向的方法。")
                        .repository("samples/order-service")
                        .focusAreas(List.of("incident", "bug_fix", "test_verification", "release_risk"))
                        .context(Map.of(
                                "serviceName", "order-service",
                                "endpoint", "POST /api/orders/submit",
                                "fixtureCase", "fixtures/incident/order-create-npe/eval-case.json",
                                "allowPatchApply", true,
                                "allowTestPatchApply", true
                        ))
                        .expectedSkills(List.of("ops_diagnosis", "repo_understanding", "engineering_knowledge_rag", "bug_fix", "release_risk_analysis"))
                        .expectedEvidenceKeywords(List.of("NullPointerException", "OrderSubmitService", "userId", "null"))
                        .expectedArtifacts(List.of("patchDraft", "riskPoints"))
                        .expectedTargetFiles(List.of("src/main/java/com/example/order/OrderSubmitService.java"))
                        .expectedTargetMethods(List.of("submit"))
                        .expectedPatchKeywords(List.of("null", "userId"))
                        .expectedTestNames(List.of())
                        .expectedRiskKeywords(List.of("NPE", "null", "回滚", "观察"))
                        .build()
                ,
                // === Phase 3 验收: test-assertion-reflection ===
                CodeOpsEvalCase.builder()
                        .caseId("test-assertion-reflection")
                        .caseName("Test Assertion 反射修复 — 并发超卖测试断言失败后 LLM 修正")
                        .taskType("INCIDENT_TO_FIX")
                        .goal("order-service 秒杀下单接口 POST /api/orders/submit 出现库存负数、重复 requestId 被处理多次。请诊断并修复并发和幂等问题，确保回归测试通过。")
                        .repository("samples/order-service")
                        .focusAreas(List.of("incident", "concurrency", "idempotency", "bug_fix", "test_verification", "release_risk"))
                        .context(Map.of(
                                "serviceName", "order-service",
                                "endpoint", "POST /api/orders/submit",
                                "fixtureCase", "fixtures/incident/inventory-oversell-concurrency/eval-case.json",
                                "allowPatchApply", true,
                                "allowTestPatchApply", true
                        ))
                        .expectedSkills(List.of("ops_diagnosis", "repo_understanding", "engineering_knowledge_rag", "bug_fix", "test_verification", "release_risk_analysis"))
                        .expectedEvidenceKeywords(List.of("库存", "并发", "requestId", "negative", "duplicate", "InventoryService"))
                        .expectedArtifacts(List.of("patchDraft", "mavenCommands", "riskPoints"))
                        .expectedTargetFiles(List.of(
                                "src/main/java/com/example/order/InventoryService.java",
                                "src/main/java/com/example/order/IdempotencyService.java",
                                "src/main/java/com/example/order/OrderSubmitService.java"
                        ))
                        .expectedTargetMethods(List.of("reserve", "submitFlashSale", "alreadyProcessed", "markProcessed"))
                        .expectedPatchKeywords(List.of("synchronized", "ConcurrentHashMap", "requestId", "stock"))
                        .expectedTestNames(List.of("InventoryConcurrencyTest", "OrderSubmitServiceConcurrencyTest"))
                        .expectedRiskKeywords(List.of("库存", "并发", "锁", "回滚", "观察"))
                        .build()
        );
    }

    private String buildTaskText(EngineeringTaskEntity task) {
        if (task == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(task.getFinalSummary()).append('\n');
        if (task.getSteps() != null) {
            for (EngineeringTaskStepEntity step : task.getSteps()) {
                builder.append(step.getSelectedSkill()).append('\n')
                        .append(step.getResultSummary()).append('\n')
                        .append(step.getRawEvidenceJson()).append('\n');
                if (step.getExpectedEvidence() != null) {
                    step.getExpectedEvidence().forEach(evidence -> builder.append(evidence).append('\n'));
                }
            }
        }
        return builder.toString();
    }

    private List<String> missing(List<String> expected, List<String> actual) {
        if (expected == null || expected.isEmpty()) {
            return List.of();
        }
        List<String> normalizedActual = actual == null ? List.of() : actual.stream().map(this::normalize).toList();
        List<String> missing = new ArrayList<>();
        for (String value : expected) {
            if (!normalizedActual.contains(normalize(value))) {
                missing.add(value);
            }
        }
        return missing;
    }

    private List<String> missingByText(List<String> expected, String normalizedText) {
        if (expected == null || expected.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String value : expected) {
            if (!normalizedText.contains(normalize(value))) {
                missing.add(value);
            }
        }
        return missing;
    }

    private List<String> hitByMissing(List<String> expected, List<String> missing) {
        if (expected == null || expected.isEmpty()) {
            return List.of();
        }
        List<String> missingValues = missing == null ? List.of() : missing;
        List<String> hit = new ArrayList<>();
        for (String value : expected) {
            if (!missingValues.contains(value)) {
                hit.add(value);
            }
        }
        return hit;
    }

    private Map<String, Object> metricDefinitions() {
        Map<String, Object> definitions = new LinkedHashMap<>();
        definitions.put("codeLocalizationCoverage", "代码定位覆盖率，检查目标文件和目标方法是否被 Agent 在输出或证据中明确提到。");
        definitions.put("patchCoverage", "修复覆盖率，检查生成的 patch 或修复说明是否覆盖 eval case 期待的关键修改点。");
        definitions.put("testCoverage", "测试覆盖率，检查测试计划、执行命令或执行结果是否包含预期相关测试。");
        definitions.put("riskCoverage", "发布风险覆盖率，检查风险报告是否覆盖上线观察、回滚、核心指标等预期风险点。");
        definitions.put("scoring", "score = hitCount / expectedCount；expectedCount 为 0 时记为 1，表示该 case 不考察该专项。");
        definitions.put("llmFirstBoundary", "这些指标评估 LLM Agent 输出是否命中目标；规则只负责从任务摘要、步骤摘要和证据 JSON 中做可重复的文本验收。");
        return definitions;
    }

    private Map<String, Object> metricDetail(BigDecimal score,
                                             List<String> expected,
                                             List<String> hit,
                                             List<String> missing,
                                             String meaning) {
        List<String> safeExpected = expected == null ? List.of() : expected;
        List<String> safeHit = hit == null ? List.of() : hit;
        List<String> safeMissing = missing == null ? List.of() : missing;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("meaning", meaning);
        detail.put("score", score);
        detail.put("expectedCount", safeExpected.size());
        detail.put("hitCount", safeHit.size());
        detail.put("missingCount", safeMissing.size());
        detail.put("expected", safeExpected);
        detail.put("hit", safeHit);
        detail.put("missing", safeMissing);
        return detail;
    }

    private List<String> concat(List<String> first, List<String> second) {
        List<String> values = new ArrayList<>();
        if (first != null) {
            values.addAll(first);
        }
        if (second != null) {
            values.addAll(second);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private BigDecimal extractDetailScore(CodeOpsEvalRun run, String metricName) {
        if (run.getDetail() == null) {
            return BigDecimal.ZERO;
        }
        Object metric = run.getDetail().get(metricName);
        if (metric instanceof Map<?, ?> metricMap) {
            Object score = ((Map<String, Object>) metricMap).get("score");
            if (score instanceof BigDecimal bigDecimal) {
                return bigDecimal;
            }
            if (score instanceof Number number) {
                return BigDecimal.valueOf(number.doubleValue());
            }
            if (score instanceof String text) {
                try {
                    return new BigDecimal(text);
                } catch (NumberFormatException ignored) {
                    return BigDecimal.ZERO;
                }
            }
        }
        if (metric instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal coverage(int expectedCount, int missingCount) {
        if (expectedCount <= 0) {
            return BigDecimal.ONE;
        }
        int hit = Math.max(0, expectedCount - missingCount);
        return BigDecimal.valueOf(hit)
                .divide(BigDecimal.valueOf(expectedCount), 4, RoundingMode.HALF_UP);
    }

    private int size(List<String> values) {
        return values == null ? 0 : values.size();
    }

    private record MemoryExtraction(String scopeType, List<String> targetMethods,
                                     String rootCause, String fixStrategy, String riskLevel) {}

    @SuppressWarnings("unchecked")
    private MemoryExtraction extractMemoryFromTask(EngineeringTaskEntity task, CodeOpsEvalCase evalCase) {
        String scopeType = "";
        List<String> targetMethods = List.of();
        String rootCause = "";
        String fixStrategy = "";
        String riskLevel = "MEDIUM";

        if (task.getSteps() != null) {
            for (EngineeringTaskStepEntity step : task.getSteps()) {
                if ("bug_fix".equals(step.getSelectedSkill())) {
                    String json = step.getRawEvidenceJson();
                    if (json != null && !json.isBlank()) {
                        try {
                            Map<String, Object> raw = com.alibaba.fastjson.JSON.parseObject(json, Map.class);
                            // Extract root cause
                            Object rc = raw.get("rootCause");
                            if (rc instanceof String s && !s.isBlank()) rootCause = s;

                            // Extract scope from guard
                            Object guard = raw.get("patchScopeGuard");
                            if (guard instanceof Map<?, ?> gm) {
                                Object rs = gm.get("repairScope");
                                if (rs instanceof Map<?, ?> rsm) {
                                    Object st = rsm.get("scopeType");
                                    if (st != null) scopeType = String.valueOf(st);
                                    Object tm = rsm.get("targetMethods");
                                    if (tm instanceof List<?> tml) {
                                        targetMethods = tml.stream().map(String::valueOf).toList();
                                    }
                                }
                            }

                            // Extract fix strategy from LLM output
                            Object reasoning = raw.get("reasoning");
                            if (reasoning instanceof List<?> rl && !rl.isEmpty()) {
                                fixStrategy = rl.stream().map(String::valueOf)
                                        .limit(3).reduce((a, b) -> a + "; " + b).orElse("");
                            }

                            // Extract risk level
                            Object confidence = raw.get("confidence");
                            if (confidence instanceof String s && !s.isBlank()) {
                                riskLevel = s;
                            }
                        } catch (Exception ignored) {}
                    }
                    break; // only need the first bug_fix step
                }
            }
        }

        // Fallbacks
        if (scopeType.isBlank()) {
            scopeType = evalCase.getExpectedSkills().contains("bug_fix") ? "CODE_FIX" : "NO_CODE_FIX";
        }
        if (targetMethods.isEmpty()) {
            targetMethods = evalCase.getExpectedTargetMethods();
        }
        if (rootCause.isBlank()) {
            rootCause = evalCase.getGoal();
        }
        if (fixStrategy.isBlank()) {
            fixStrategy = scopeType.contains("NO_CODE_FIX") ? "no_code_fix_needed" : "llm_determined";
        }

        return new MemoryExtraction(scopeType, targetMethods, rootCause, fixStrategy, riskLevel);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\p{IsHan}]+", " ")
                .trim();
    }

}
