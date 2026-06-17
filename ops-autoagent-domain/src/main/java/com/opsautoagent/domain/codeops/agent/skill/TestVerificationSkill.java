package com.opsautoagent.domain.codeops.agent.skill;

import com.opsautoagent.domain.codeops.agent.patch.PatchApplyResult;
import com.opsautoagent.domain.codeops.agent.patch.PatchApplyService;
import com.opsautoagent.domain.codeops.agent.patch.FileRewritePatchEntity;
import com.opsautoagent.domain.codeops.agent.patch.PatchValidationResult;
import com.opsautoagent.domain.codeops.agent.patch.PatchValidationService;
import com.opsautoagent.domain.codeops.agent.test.CodeOpsTestVerificationAgentInput;
import com.opsautoagent.domain.codeops.agent.test.CodeOpsTestVerificationAgentOutput;
import com.opsautoagent.domain.codeops.agent.test.CodeOpsTestVerificationAgentService;
import com.opsautoagent.domain.codeops.agent.test.IncidentRegressionScaffoldService;
import com.opsautoagent.domain.codeops.agent.testpatch.CodeOpsTestPatchAgentInput;
import com.opsautoagent.domain.codeops.agent.testpatch.CodeOpsTestPatchAgentOutput;
import com.opsautoagent.domain.codeops.agent.testpatch.CodeOpsTestPatchAgentService;
import com.opsautoagent.domain.codeops.agent.task.BackgroundToolTaskService;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolGateway;
import com.opsautoagent.domain.codeops.model.entity.CodeSnippetEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffContextEntity;
import com.opsautoagent.domain.codeops.model.entity.TestVerificationPlanEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class TestVerificationSkill implements EngineeringSkill {

    public static final String SKILL_ID = "test_verification";

    private final EngineeringToolGateway toolGateway;

    private final BackgroundToolTaskService backgroundToolTaskService;

    private final CodeOpsTestVerificationAgentService testVerificationAgentService;

    private final CodeOpsTestPatchAgentService testPatchAgentService;

    private final PatchValidationService patchValidationService;

    private final PatchApplyService patchApplyService;

    private final IncidentRegressionScaffoldService incidentRegressionScaffoldService;

    @Value("${codeops.test.execution.enabled:false}")
    private boolean testExecutionEnabled;

    @Value("${codeops.test.execution.timeout-ms:120000}")
    private long testExecutionTimeoutMs;

    public TestVerificationSkill(EngineeringToolGateway toolGateway,
                                 BackgroundToolTaskService backgroundToolTaskService,
                                 CodeOpsTestVerificationAgentService testVerificationAgentService,
                                 CodeOpsTestPatchAgentService testPatchAgentService,
                                 PatchValidationService patchValidationService,
                                 PatchApplyService patchApplyService,
                                 IncidentRegressionScaffoldService incidentRegressionScaffoldService) {
        this.toolGateway = toolGateway;
        this.backgroundToolTaskService = backgroundToolTaskService;
        this.testVerificationAgentService = testVerificationAgentService;
        this.testPatchAgentService = testPatchAgentService;
        this.patchValidationService = patchValidationService;
        this.patchApplyService = patchApplyService;
        this.incidentRegressionScaffoldService = incidentRegressionScaffoldService;
    }

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("Test Verification Skill")
                .description("Build a verification plan from changed files and related tests, including Maven commands and coverage gaps.")
                .supportedTaskTypes(List.of("ISSUE_TO_PATCH", "INCIDENT_TO_FIX", "CODE_REVIEW", "RELEASE_RISK", "BUG_FIX"))
                .requiredTools(List.of("repo.git_diff", "repo.find_tests"))
                .riskLevel("READ_ONLY")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        RepoDiffContextEntity diffContext = toolGateway.loadDiffContext(task.getRepository(), task.getChangeRef(), task.getContext());
        Map<String, Object> codeLocalization = codeLocalizationOutput(task);
        Map<String, Object> patchGeneration = skillOutput(task, BugFixSkill.SKILL_ID);
        String verificationRepositoryPath = firstNonBlank(
                objectString(patchGeneration.get("sandboxRepositoryPath")),
                objectString(patchGeneration.get("repositoryPath")),
                value(diffContext.getRepositoryPath(), ""));
        List<String> relatedTestFiles = resolveRelatedTestFiles(task, diffContext, codeLocalization);
        TestVerificationPlanEntity baselinePlan = TestVerificationPlanEntity.builder()
                .repositoryPath(verificationRepositoryPath)
                .changeRef(value(diffContext.getChangeRef(), "working_tree"))
                .changedFiles(list(diffContext.getChangedFiles()))
                .relatedTestFiles(relatedTestFiles)
                .recommendedTests(buildRecommendedTests(diffContext, relatedTestFiles, codeLocalization))
                .coverageGaps(buildCoverageGaps(diffContext, relatedTestFiles, codeLocalization))
                .mavenCommands(buildMavenCommands(relatedTestFiles))
                .verificationNotes(buildVerificationNotes(task, diffContext))
                .testExecutionResults(List.of())
                .build();
        boolean mergedRepairAndTestAgent = "INCIDENT_TO_FIX".equals(task.getTaskType()) && !patchGeneration.isEmpty();
        CodeOpsTestVerificationAgentOutput agentOutput;
        TestVerificationPlanEntity plan;
        if (mergedRepairAndTestAgent) {
            plan = mergedRepairTestPlan(baselinePlan, patchGeneration);
            List<String> scaffoldCommands = incidentRegressionScaffoldService.mavenCommandsIfSupported(
                    plan.getRepositoryPath(), task, codeLocalization, patchGeneration);
            if (!scaffoldCommands.isEmpty()) {
                plan.setMavenCommands(scaffoldCommands);
                List<String> scaffoldTestFiles = scaffoldRelatedTestFiles(plan.getRepositoryPath());
                List<String> recommended = new ArrayList<>(List.of(
                        "InventoryConcurrencyTest：验证库存扣减并发下不会超卖",
                        "OrderSubmitServiceConcurrencyTest：验证重复 requestId 并发下只创建一笔订单"));
                if (scaffoldTestFiles.stream().anyMatch(file -> file.endsWith("IdempotencyServiceAtomicityTest.java"))) {
                    recommended.add("IdempotencyServiceAtomicityTest：验证幂等组件自身提供原子 check-and-mark API");
                }
                plan.setRecommendedTests(recommended);
                plan.setRelatedTestFiles(scaffoldTestFiles);
            }
            agentOutput = CodeOpsTestVerificationAgentOutput.unavailable(
                    "Incident-to-Fix uses the combined Code Repair & Test Agent output; no separate Test Plan LLM call is made.",
                    plan);
        } else {
            agentOutput = testVerificationAgentService.plan(CodeOpsTestVerificationAgentInput.builder()
                    .taskId(task.getTaskId())
                    .taskType(task.getTaskType())
                    .goal(task.getGoal())
                    .repositoryPath(value(diffContext.getRepositoryPath(), ""))
                    .changeRef(value(diffContext.getChangeRef(), "working_tree"))
                    .diffSummary(value(diffContext.getDiffSummary(), ""))
                    .changedFiles(list(diffContext.getChangedFiles()))
                    .relatedTestFiles(relatedTestFiles)
                    .codeLocalization(codeLocalization)
                    .patchGeneration(patchGeneration)
                    .baselinePlan(baselinePlan)
                    .build());
            plan = agentOutput.getPlan() == null ? baselinePlan : agentOutput.getPlan();
        }
        List<CodeSnippetEntity> testSnippets = loadTestSnippets(plan.getRepositoryPath(), plan.getRelatedTestFiles());
        CodeOpsTestPatchAgentOutput testPatch = mergedRepairAndTestAgent
                ? mergedRepairTestPatch(patchGeneration)
                : testPatchAgentService.proposeTestPatch(CodeOpsTestPatchAgentInput.builder()
                        .taskId(task.getTaskId())
                        .taskType(task.getTaskType())
                        .goal(task.getGoal())
                        .repositoryPath(plan.getRepositoryPath())
                        .relatedTestFiles(plan.getRelatedTestFiles())
                        .codeLocalization(codeLocalization)
                        .patchGeneration(patchGeneration)
                        .testSnippets(testSnippets)
                        .build());
        CodeOpsTestPatchAgentOutput scaffoldPatch = incidentRegressionScaffoldService.generateIfSupported(
                plan.getRepositoryPath(), task, codeLocalization, patchGeneration);
        if (scaffoldPatch.isSuccess()) {
            testPatch = scaffoldPatch;
        }
        String rewriteTestPatch = buildPatchFromFileRewrites(plan.getRepositoryPath(), testPatch.getFileRewrites());
        String testPatchText = !isBlank(rewriteTestPatch) ? rewriteTestPatch : value(testPatch.getUnifiedDiffPatch(), "");
        PatchValidationResult testPatchValidation = patchValidationService.validate(
                plan.getRepositoryPath(),
                testPatchText);
        Map<String, String> testFileSnapshot = snapshotFiles(plan.getRepositoryPath(), testPatchValidation.getExistingTouchedFiles());
        List<String> newTestFiles = missingFiles(plan.getRepositoryPath(), testPatchValidation.getTouchedFiles());
        PatchApplyResult testPatchApply = shouldApplyTestPatch(task)
                ? patchApplyService.apply(plan.getRepositoryPath(), testPatchText)
                : PatchApplyResult.skipped(plan.getRepositoryPath(), "Test patch apply disabled. Set task context allowTestPatchApply=true to modify test files.");
        List<String> skippedCommands = adjustCommandsForUnappliedTestPatch(plan, testPatchValidation, testPatchApply);
        plan.setTestExecutionResults(runVerificationCommands(task, diffContext, plan));
        List<Map<String, Object>> queuedBackgroundTasks = extractQueuedBackgroundTasks(plan.getTestExecutionResults());
        boolean waitingBackgroundVerification = isAsyncTestExecution(task) && !queuedBackgroundTasks.isEmpty();
        boolean testsFailed = hasFailedTestResult(plan.getTestExecutionResults());
        boolean compileFailed = hasCompilationFailure(plan.getTestExecutionResults());
        boolean testPatchRolledBack = false;
        if (testPatchApply.isApplied() && testsFailed && (compileFailed || containsPatchTouchedTest(plan.getTestExecutionResults(), testPatchValidation.getTouchedFiles()))) {
            boolean restoredExisting = restoreFiles(plan.getRepositoryPath(), testFileSnapshot);
            boolean removedNew = deleteFiles(plan.getRepositoryPath(), newTestFiles);
            testPatchRolledBack = restoredExisting || removedNew;
        }
        Map<String, Object> rawOutput = new LinkedHashMap<>();
        rawOutput.put("phase", "PHASE_5_LLM_TEST_VERIFICATION");
        rawOutput.put("repositoryPath", plan.getRepositoryPath());
        rawOutput.put("originalRepositoryPath", value(diffContext.getRepositoryPath(), ""));
        rawOutput.put("sandboxRepositoryPath", objectString(patchGeneration.get("sandboxRepositoryPath")));
        rawOutput.put("testExecutionRepositoryPath", plan.getRepositoryPath());
        rawOutput.put("testExecutionAsync", isAsyncTestExecution(task));
        rawOutput.put("changeRef", plan.getChangeRef());
        rawOutput.put("changedFiles", plan.getChangedFiles());
        rawOutput.put("relatedTestFiles", plan.getRelatedTestFiles());
        rawOutput.put("recommendedTests", plan.getRecommendedTests());
        rawOutput.put("coverageGaps", plan.getCoverageGaps());
        rawOutput.put("mavenCommands", plan.getMavenCommands());
        rawOutput.put("skippedMavenCommands", skippedCommands);
        rawOutput.put("queuedBackgroundTasks", queuedBackgroundTasks);
        rawOutput.put("backgroundVerificationPending", waitingBackgroundVerification);
        rawOutput.put("backgroundVerificationStatus", waitingBackgroundVerification ? "RUNNING" : "");
        rawOutput.put("backgroundToolTasks", task.getContext() == null
                ? List.of()
                : task.getContext().getOrDefault(BackgroundToolTaskService.BACKGROUND_TOOL_TASKS_KEY, List.of()));
        rawOutput.put("taskNotifications", task.getContext() == null
                ? List.of()
                : task.getContext().getOrDefault(BackgroundToolTaskService.TASK_NOTIFICATIONS_KEY, List.of()));
        rawOutput.put("verificationNotes", plan.getVerificationNotes());
        rawOutput.put("testExecutionResults", plan.getTestExecutionResults());
        rawOutput.put("baselinePlan", planToMap(baselinePlan));
        rawOutput.put("llmTestPlanSuccess", agentOutput.isSuccess());
        rawOutput.put("llmTestPlanFallback", agentOutput.isFallback());
        rawOutput.put("mergedRepairAndTestAgent", mergedRepairAndTestAgent);
        rawOutput.put("testPlanReasoning", agentOutput.getReasoning() == null ? List.of() : agentOutput.getReasoning());
        rawOutput.put("llmTestPlanError", value(agentOutput.getErrorMessage(), ""));
        rawOutput.put("testSnippets", testSnippets);
        rawOutput.put("testPatchGenerated", testPatch.isSuccess() && !isBlank(testPatchText));
        rawOutput.put("testPatchScaffolded", scaffoldPatch.isSuccess());
        rawOutput.put("testPatchTargetFiles", testPatch.getTargetTestFiles() == null ? List.of() : testPatch.getTargetTestFiles());
        rawOutput.put("testPatchReasoning", testPatch.getReasoning() == null ? List.of() : testPatch.getReasoning());
        rawOutput.put("testPatchDraft", testPatchText);
        rawOutput.put("testPatchError", value(testPatch.getErrorMessage(), ""));
        rawOutput.put("testPatchValidation", testPatchValidation.toRawOutput());
        rawOutput.put("testPatchApply", testPatchApply.toRawOutput());
        rawOutput.put("verificationBlockedReason", verificationBlockedReason(testPatchValidation, testPatchApply, skippedCommands));
        rawOutput.put("testPatchRolledBack", testPatchRolledBack);
        rawOutput.put("testPatchRollbackReason", testPatchRolledBack
                ? "测试补丁已应用但导致编译失败，已回滚测试文件，避免坏测试残留在工作区。"
                : "");

        // Standardized failure output
        String testOutputText = String.join("\n", plan.getTestExecutionResults() == null
                ? List.of() : plan.getTestExecutionResults());
        rawOutput.put("testFailureType", testsFailed ? detectTestFailureType(testOutputText) : "");
        rawOutput.put("failedCommands", extractFailedCommands(testOutputText));
        rawOutput.put("failedTestFiles", extractFailedTestFiles(testOutputText));
        rawOutput.put("failedAssertions", extractFailedAssertions(testOutputText));
        rawOutput.put("rawFailureSummary", abbreviate(testOutputText, 1500));
        boolean testsPassed = hasPassingTestResult(plan.getTestExecutionResults());
        rawOutput.put("testsPassed", testsPassed);

        boolean testPatchRequiredButFailed = shouldApplyTestPatch(task)
                && testPatch.isSuccess()
                && !isBlank(testPatchText)
                && !testPatchApply.isApplied();
        String status = waitingBackgroundVerification
                ? "WAITING_BACKGROUND_TASK"
                : (testPatchRequiredButFailed || testsFailed)
                ? "FAILED"
                : (testsPassed || Boolean.TRUE.equals(diffContext.getDiffAvailable()) ? "SUCCESS" : "NO_DIFF");
        String summary = waitingBackgroundVerification
                ? "已生成测试验证计划并提交后台 Maven 验证：" + queuedBackgroundTasks.size() + " 个任务运行中。"
                : "已生成测试验证计划：建议测试 "
                + plan.getRecommendedTests().size()
                + " 项，覆盖缺口 "
                + plan.getCoverageGaps().size()
                + " 项。";

        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status(status)
                .summary(summary)
                .evidence(List.of(
                        "任务目标：" + value(task.getGoal(), "未提供"),
                        "变更文件：" + join(plan.getChangedFiles()),
                        "相关测试：" + join(plan.getRelatedTestFiles()),
                        "Maven 建议：" + join(plan.getMavenCommands())
                        , "测试执行：" + join(plan.getTestExecutionResults())
                ))
                .nextActions(List.of("先运行推荐的定向测试", "若无相关测试则补充最小回归用例", "最后运行模块级 compile/test 作为兜底验证"))
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

    private Map<String, Object> codeLocalizationOutput(EngineeringTaskEntity task) {
        Map<String, Object> repoOutput = skillOutput(task, RepoUnderstandingSkill.SKILL_ID);
        if (!repoOutput.isEmpty()) {
            return repoOutput;
        }
        return skillOutput(task, AgentLoopEngineeringSkill.SKILL_ID);
    }

    private TestVerificationPlanEntity mergedRepairTestPlan(TestVerificationPlanEntity baselinePlan,
                                                            Map<String, Object> patchGeneration) {
        List<String> mavenCommands = stringList(patchGeneration.get("mavenCommands"));
        List<String> testSuggestions = stringList(patchGeneration.get("testSuggestions"));
        return TestVerificationPlanEntity.builder()
                .repositoryPath(baselinePlan.getRepositoryPath())
                .changeRef(baselinePlan.getChangeRef())
                .changedFiles(baselinePlan.getChangedFiles())
                .relatedTestFiles(baselinePlan.getRelatedTestFiles())
                .recommendedTests(testSuggestions.isEmpty() ? baselinePlan.getRecommendedTests() : testSuggestions)
                .coverageGaps(baselinePlan.getCoverageGaps())
                .mavenCommands(mavenCommands.isEmpty() ? baselinePlan.getMavenCommands() : mavenCommands)
                .verificationNotes(List.of("测试计划来自合并后的 Code Repair & Test Agent 输出，未额外调用 Test Plan LLM。"))
                .testExecutionResults(List.of())
                .build();
    }

    private Map<String, Object> planToMap(TestVerificationPlanEntity plan) {
        if (plan == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repositoryPath", value(plan.getRepositoryPath(), ""));
        result.put("changeRef", value(plan.getChangeRef(), ""));
        result.put("changedFiles", list(plan.getChangedFiles()));
        result.put("relatedTestFiles", list(plan.getRelatedTestFiles()));
        result.put("recommendedTests", list(plan.getRecommendedTests()));
        result.put("coverageGaps", list(plan.getCoverageGaps()));
        result.put("mavenCommands", list(plan.getMavenCommands()));
        result.put("verificationNotes", list(plan.getVerificationNotes()));
        result.put("testExecutionResults", list(plan.getTestExecutionResults()));
        return result;
    }

    private CodeOpsTestPatchAgentOutput mergedRepairTestPatch(Map<String, Object> patchGeneration) {
        String unifiedDiffPatch = value(objectString(patchGeneration.get("testUnifiedDiffPatch")), "");
        List<FileRewritePatchEntity> rewrites = fileRewriteList(patchGeneration.get("testFileRewrites"));
        boolean hasPatch = !isBlank(unifiedDiffPatch) || !rewrites.isEmpty();
        return CodeOpsTestPatchAgentOutput.builder()
                .success(hasPatch)
                .fallback(!hasPatch)
                .targetTestFiles(targetFilesFromRewritesOrPatch(rewrites, unifiedDiffPatch))
                .reasoning(List.of("测试补丁来自合并后的 Code Repair & Test Agent 输出，未额外调用 Test Patch LLM。"))
                .unifiedDiffPatch(unifiedDiffPatch)
                .fileRewrites(rewrites)
                .rawContent("")
                .errorMessage(hasPatch ? "" : "Combined Code Repair & Test Agent did not provide a concrete test patch.")
                .costMillis(0L)
                .createTime(java.time.LocalDateTime.now())
                .build();
    }

    private List<String> runVerificationCommands(EngineeringTaskEntity task, RepoDiffContextEntity diffContext, TestVerificationPlanEntity plan) {
        List<String> plannedCommands = plan == null ? List.of() : list(plan.getMavenCommands());
        if (!testExecutionEnabled) {
            for (String command : plannedCommands) {
                List<String> args = parseMavenArgs(command);
                if (!args.isEmpty()) {
                    backgroundToolTaskService.recordSkippedMaven(task, "test_verification",
                            plan == null ? "" : plan.getRepositoryPath(), args,
                            "真实测试执行未开启，Maven 后台任务仅记录计划，不启动本地进程。");
                }
            }
            return List.of("真实测试执行未开启：设置 codeops.test.execution.enabled=true 后会运行推荐 Maven 命令。");
        }
        List<String> results = new ArrayList<>();
        if (isAsyncTestExecution(task)) {
            if (!plannedCommands.isEmpty()) {
                for (String command : plannedCommands) {
                    List<String> args = parseMavenArgs(command);
                    if (args.isEmpty()) {
                        continue;
                    }
                    var backgroundTask = backgroundToolTaskService.startMavenAsync(
                            task, "test_verification", plan.getRepositoryPath(), args, testExecutionTimeoutMs);
                    results.add("backgroundTaskId=" + backgroundTask.getBackgroundTaskId()
                            + ", command=" + backgroundTask.getRequestSummary()
                            + ", status=" + backgroundTask.getStatus()
                            + ", async=true");
                }
                return results;
            }
            List<String> asyncRelatedTests = resolveRelatedTestFiles(task, diffContext, skillOutput(task, RepoUnderstandingSkill.SKILL_ID));
            List<String> asyncArgs = new ArrayList<>();
            asyncArgs.add("-q");
            if (!asyncRelatedTests.isEmpty()) {
                asyncArgs.add("-Dtest=" + buildTestSelector(asyncRelatedTests));
                asyncArgs.add("test");
            } else {
                asyncArgs.add("-DskipTests");
                asyncArgs.add("compile");
            }
            var backgroundTask = backgroundToolTaskService.startMavenAsync(
                    task, "test_verification", plan.getRepositoryPath(), asyncArgs, testExecutionTimeoutMs);
            results.add("backgroundTaskId=" + backgroundTask.getBackgroundTaskId()
                    + ", command=" + backgroundTask.getRequestSummary()
                    + ", status=" + backgroundTask.getStatus()
                    + ", async=true");
            return results;
        }
        if (!plannedCommands.isEmpty()) {
            for (String command : plannedCommands) {
                List<String> args = parseMavenArgs(command);
                if (args.isEmpty()) {
                    continue;
                }
                EngineeringToolGateway.CommandResult result = backgroundToolTaskService.runMavenAndRecord(
                        task, "test_verification", plan.getRepositoryPath(), args, testExecutionTimeoutMs);
                results.add("command=" + String.join(" ", result.command())
                        + ", success=" + result.success()
                        + ", exitCode=" + result.exitCode()
                        + ", costMillis=" + result.costMillis()
                        + ", output=" + abbreviate(result.output(), 1200));
                if (!result.success()) {
                    return results;
                }
            }
            return results;
        }
        List<String> relatedTests = resolveRelatedTestFiles(task, diffContext, skillOutput(task, RepoUnderstandingSkill.SKILL_ID));
        List<String> args = new ArrayList<>();
        args.add("-q");
        if (!relatedTests.isEmpty()) {
            args.add("-Dtest=" + buildTestSelector(relatedTests));
            args.add("test");
        } else {
            args.add("-DskipTests");
            args.add("compile");
        }
        EngineeringToolGateway.CommandResult result = backgroundToolTaskService.runMavenAndRecord(
                task, "test_verification", plan.getRepositoryPath(), args, testExecutionTimeoutMs);
        results.add("command=" + String.join(" ", result.command())
                + ", success=" + result.success()
                + ", exitCode=" + result.exitCode()
                + ", costMillis=" + result.costMillis()
                + ", output=" + abbreviate(result.output(), 1200));
        return results;
    }

    private List<Map<String, Object>> extractQueuedBackgroundTasks(List<String> testExecutionResults) {
        List<Map<String, Object>> queued = new ArrayList<>();
        for (String result : list(testExecutionResults)) {
            if (isBlank(result) || !result.contains("backgroundTaskId=")) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("backgroundTaskId", extractDelimitedValue(result, "backgroundTaskId=", ","));
            item.put("command", extractDelimitedValue(result, "command=", ", status="));
            item.put("status", extractDelimitedValue(result, "status=", ","));
            item.put("async", result.contains("async=true"));
            queued.add(item);
        }
        return queued;
    }

    private String extractDelimitedValue(String text, String prefix, String delimiter) {
        int start = text.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        start += prefix.length();
        int end = delimiter == null || delimiter.isEmpty() ? -1 : text.indexOf(delimiter, start);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end).trim();
    }

    private List<String> adjustCommandsForUnappliedTestPatch(TestVerificationPlanEntity plan,
                                                             PatchValidationResult validation,
                                                             PatchApplyResult applyResult) {
        if (plan == null || validation == null || applyResult == null || applyResult.isApplied()) {
            return List.of();
        }
        List<String> missingTests = list(validation.getMissingTouchedFiles()).stream()
                .filter(this::isTestFile)
                .map(this::testClassName)
                .filter(value -> !value.isBlank())
                .toList();
        if (missingTests.isEmpty()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String command : list(plan.getMavenCommands())) {
            CommandFilterResult filterResult = filterMissingTestSelectors(command, missingTests);
            if (filterResult.skipped()) {
                skipped.add(command + " [skipped: test patch was not applied]");
            } else if (!filterResult.command().equals(command)) {
                kept.add(filterResult.command());
                skipped.add(command + " [filtered missing tests: " + String.join(", ", filterResult.removedTests()) + "]");
            } else {
                kept.add(command);
            }
        }
        if (kept.isEmpty()) {
            kept.add("mvn -q -DskipTests compile");
        }
        if (!skipped.isEmpty()) {
            plan.setMavenCommands(kept);
            List<String> notes = new ArrayList<>(list(plan.getVerificationNotes()));
            notes.add("测试补丁未应用，已跳过仅针对新测试类的 Maven 命令，避免将“测试类不存在”误判为代码失败。");
            plan.setVerificationNotes(notes);
        }
        return skipped;
    }

    private CommandFilterResult filterMissingTestSelectors(String command, List<String> missingTests) {
        if (isBlank(command) || missingTests == null || missingTests.isEmpty()) {
            return new CommandFilterResult(command, false, List.of());
        }
        String normalized = command.replace("\"", "").replace("'", "");
        int index = normalized.indexOf("-Dtest=");
        if (index < 0) {
            return new CommandFilterResult(command, false, List.of());
        }
        String selector = normalized.substring(index + "-Dtest=".length()).trim();
        int space = selector.indexOf(' ');
        if (space >= 0) {
            selector = selector.substring(0, space);
        }
        if (selector.isBlank()) {
            return new CommandFilterResult(command, false, List.of());
        }
        List<String> selected = List.of(selector.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        if (selected.isEmpty()) {
            return new CommandFilterResult(command, false, List.of());
        }
        List<String> kept = selected.stream().filter(item -> !missingTests.contains(item)).toList();
        List<String> removed = selected.stream().filter(missingTests::contains).toList();
        if (removed.isEmpty()) {
            return new CommandFilterResult(command, false, List.of());
        }
        if (kept.isEmpty()) {
            return new CommandFilterResult(command, true, removed);
        }
        String updated = command.substring(0, index + "-Dtest=".length())
                + String.join(",", kept)
                + command.substring(index + "-Dtest=".length() + selector.length());
        return new CommandFilterResult(updated, false, removed);
    }

    private String verificationBlockedReason(PatchValidationResult validation,
                                             PatchApplyResult applyResult,
                                             List<String> skippedCommands) {
        if (skippedCommands == null || skippedCommands.isEmpty()) {
            return "";
        }
        String reason = applyResult == null ? "" : value(applyResult.getErrorMessage(), "");
        return "Test patch was not applied"
                + (reason.isBlank() ? "" : ": " + reason)
                + ". Missing test files="
                + join(validation == null ? List.of() : validation.getMissingTouchedFiles());
    }

    private List<String> parseMavenArgs(String command) {
        if (isBlank(command)) {
            return List.of();
        }
        String normalized = command.trim();
        if (normalized.startsWith("mvn.cmd")) {
            normalized = normalized.substring("mvn.cmd".length()).trim();
        } else if (normalized.startsWith("mvn")) {
            normalized = normalized.substring("mvn".length()).trim();
        }
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (String part : normalized.split("\\s+")) {
            if (!part.isBlank() && !part.equals("-f") && !part.contains(":")) {
                args.add(part);
            }
        }
        return args;
    }

    private List<String> scaffoldRelatedTestFiles(String repositoryPath) {
        List<String> files = new ArrayList<>(List.of(
                "src/test/java/com/example/order/InventoryConcurrencyTest.java",
                "src/test/java/com/example/order/OrderSubmitServiceConcurrencyTest.java"));
        if (!isBlank(repositoryPath)) {
            Path repo = Path.of(repositoryPath).toAbsolutePath().normalize();
            Path atomicity = repo.resolve("src/test/java/com/example/order/IdempotencyServiceAtomicityTest.java").normalize();
            if (atomicity.startsWith(repo) && Files.exists(atomicity)) {
                files.add("src/test/java/com/example/order/IdempotencyServiceAtomicityTest.java");
            }
        }
        return files;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> values) {
            return values.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    private String objectString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<FileRewritePatchEntity> fileRewriteList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<FileRewritePatchEntity> rewrites = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof FileRewritePatchEntity rewrite) {
                rewrites.add(rewrite);
            } else if (item instanceof Map<?, ?> map) {
                rewrites.add(FileRewritePatchEntity.builder()
                        .filePath(String.valueOf(map.get("filePath")))
                        .newContent(String.valueOf(map.get("newContent")))
                        .reasoning(String.valueOf(map.get("reasoning")))
                        .build());
            }
        }
        return rewrites;
    }

    private List<String> targetFilesFromRewritesOrPatch(List<FileRewritePatchEntity> rewrites, String patch) {
        List<String> files = new ArrayList<>();
        for (FileRewritePatchEntity rewrite : rewrites == null ? List.<FileRewritePatchEntity>of() : rewrites) {
            if (!isBlank(rewrite.getFilePath())) {
                files.add(rewrite.getFilePath());
            }
        }
        if (!isBlank(patch)) {
            for (String line : patch.split("\n")) {
                if (line.startsWith("+++ ")) {
                    String path = line.substring(4).trim();
                    if (path.startsWith("b/")) {
                        path = path.substring(2);
                    }
                    if (!"/dev/null".equals(path) && !path.isBlank()) {
                        files.add(path);
                    }
                }
            }
        }
        return files.stream().distinct().toList();
    }

    private boolean hasFailedTestResult(List<String> testExecutionResults) {
        for (String result : list(testExecutionResults)) {
            if (result.contains("success=false") || result.contains("exitCode=1")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCompilationFailure(List<String> testExecutionResults) {
        for (String result : list(testExecutionResults)) {
            String lower = result.toLowerCase();
            if (lower.contains("compilation error")
                    || lower.contains("testcompile")
                    || lower.contains("compilation failure")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPassingTestResult(List<String> testExecutionResults) {
        List<String> results = list(testExecutionResults);
        if (results.isEmpty()) {
            return false;
        }
        String text = String.join("\n", results).toLowerCase(Locale.ROOT);
        if (text.contains("真实测试执行未开启") || hasFailedTestResult(results)) {
            return false;
        }
        return text.contains("success=true")
                || text.contains("build success")
                || (text.contains("tests run:") && text.contains("failures: 0") && text.contains("errors: 0"));
    }

    private Map<String, String> snapshotFiles(String repositoryPath, List<String> files) {
        if (isBlank(repositoryPath)) {
            return Map.of();
        }
        Path repo = Path.of(repositoryPath).toAbsolutePath().normalize();
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String filePath : list(files)) {
            if (isBlank(filePath) || !filePath.replace('\\', '/').contains("/src/test/")) {
                continue;
            }
            Path file = repo.resolve(filePath).normalize();
            if (!file.startsWith(repo) || !Files.exists(file) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                snapshot.put(filePath, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Snapshot is best effort. If a file cannot be read, do not attempt rollback for it.
            }
        }
        return snapshot;
    }

    private List<String> missingFiles(String repositoryPath, List<String> files) {
        if (isBlank(repositoryPath) || files == null || files.isEmpty()) {
            return List.of();
        }
        Path repoRoot = Path.of(repositoryPath).toAbsolutePath().normalize();
        List<String> missing = new ArrayList<>();
        for (String filePath : files) {
            if (isBlank(filePath)) {
                continue;
            }
            Path file = repoRoot.resolve(filePath).normalize();
            if (file.startsWith(repoRoot) && !Files.exists(file)) {
                missing.add(filePath);
            }
        }
        return missing;
    }

    private boolean restoreFiles(String repositoryPath, Map<String, String> snapshot) {
        if (isBlank(repositoryPath) || snapshot == null || snapshot.isEmpty()) {
            return false;
        }
        Path repo = Path.of(repositoryPath).toAbsolutePath().normalize();
        boolean restoredAny = false;
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            Path file = repo.resolve(entry.getKey()).normalize();
            if (!file.startsWith(repo)) {
                continue;
            }
            try {
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
                restoredAny = true;
            } catch (IOException ignored) {
                // Keep the failure visible in testExecutionResults; rollback is a safety net, not the source of truth.
            }
        }
        return restoredAny;
    }

    private boolean deleteFiles(String repositoryPath, List<String> files) {
        if (isBlank(repositoryPath) || files == null || files.isEmpty()) {
            return false;
        }
        Path repoRoot = Path.of(repositoryPath).toAbsolutePath().normalize();
        boolean deletedAny = false;
        for (String filePath : files) {
            if (isBlank(filePath)) {
                continue;
            }
            Path file = repoRoot.resolve(filePath).normalize();
            if (!file.startsWith(repoRoot)) {
                continue;
            }
            try {
                deletedAny = Files.deleteIfExists(file) || deletedAny;
            } catch (IOException ignored) {
                // Keep the compile/test failure visible for the next reflection round.
            }
        }
        return deletedAny;
    }

    private boolean containsPatchTouchedTest(List<String> testExecutionResults, List<String> touchedFiles) {
        if (testExecutionResults == null || touchedFiles == null || touchedFiles.isEmpty()) {
            return false;
        }
        String text = String.join("\n", testExecutionResults).replace('\\', '/').toLowerCase();
        for (String file : touchedFiles) {
            if (!isBlank(file) && text.contains(file.replace('\\', '/').toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildRecommendedTests(RepoDiffContextEntity diffContext,
                                               List<String> relatedTestFiles,
                                               Map<String, Object> codeLocalization) {
        Set<String> tests = new LinkedHashSet<>();
        for (String test : stringList(codeLocalization.get("recommendedTests"))) {
            if (!isBlank(test)) {
                tests.add(test);
            }
        }
        for (String testFile : list(relatedTestFiles)) {
            tests.add(testFile + "：直接相关回归测试");
        }
        if (tests.isEmpty()) {
            for (String file : list(diffContext.getChangedFiles())) {
                if (file.endsWith(".java") && !file.contains("/src/test/") && !file.contains("\\src\\test\\")) {
                    tests.add(file + "：未发现配套测试，建议新增同名 Test/Tests 覆盖核心分支。");
                }
            }
        }
        if (tests.isEmpty()) {
            for (String file : stringList(codeLocalization.get("targetFiles"))) {
                if (file.endsWith(".java") && !file.contains("/src/test/") && !file.contains("\\src\\test\\")) {
                    tests.add(file + "：来自 agent loop 代码定位，建议新增或运行同名 Test/Tests 覆盖核心分支。");
                }
            }
        }
        return tests.isEmpty() ? List.of("当前没有 Java 变更或相关测试，建议先运行编译验证。") : new ArrayList<>(tests);
    }

    private List<String> buildCoverageGaps(RepoDiffContextEntity diffContext,
                                           List<String> relatedTestFiles,
                                           Map<String, Object> codeLocalization) {
        List<String> gaps = new ArrayList<>();
        List<String> relatedTests = list(relatedTestFiles);
        for (String file : list(diffContext.getChangedFiles())) {
            if (file.endsWith(".java") && !file.contains("/src/test/") && !file.contains("\\src\\test\\") && relatedTests.isEmpty()) {
                gaps.add(file + " 缺少自动识别到的同名测试。");
            }
            if (file.toLowerCase().contains("controller")) {
                gaps.add(file + " 建议覆盖 HTTP 入参、异常映射和返回码。");
            }
            if (file.toLowerCase().contains("service")) {
                gaps.add(file + " 建议覆盖主流程、异常分支、事务/幂等边界。");
            }
            if (file.toLowerCase().contains("repository") || file.toLowerCase().contains("mapper")) {
                gaps.add(file + " 建议覆盖 SQL 条件、空结果和边界分页。");
            }
        }
        if (gaps.isEmpty() && relatedTests.isEmpty()) {
            for (String file : stringList(codeLocalization.get("targetFiles"))) {
                if (file.endsWith(".java") && !file.contains("/src/test/") && !file.contains("\\src\\test\\")) {
                    gaps.add(file + " 来自 agent loop 定位，但未发现相关测试文件。");
                }
            }
        }
        return gaps.isEmpty() ? List.of("暂未发现明显测试覆盖缺口，仍建议结合任务目标人工确认关键路径。") : gaps;
    }

    private List<String> buildMavenCommands(List<String> relatedTestFiles) {
        List<String> commands = new ArrayList<>();
        commands.add("mvn -q -DskipTests compile");
        List<String> relatedTests = list(relatedTestFiles);
        if (!relatedTests.isEmpty()) {
            commands.add("mvn -q -Dtest=" + buildTestSelector(relatedTests) + " test");
        } else {
            commands.add("mvn -q test");
        }
        return commands;
    }

    private String buildTestSelector(List<String> relatedTests) {
        List<String> names = new ArrayList<>();
        for (String testFile : relatedTests) {
            String fileName = testFile.replace('\\', '/');
            int slash = fileName.lastIndexOf('/');
            if (slash >= 0) {
                fileName = fileName.substring(slash + 1);
            }
            if (fileName.endsWith(".java")) {
                fileName = fileName.substring(0, fileName.length() - ".java".length());
            }
            if (!fileName.isBlank()) {
                names.add(fileName);
            }
        }
        return names.isEmpty() ? "*" : String.join(",", names);
    }

    private List<String> buildVerificationNotes(EngineeringTaskEntity task, RepoDiffContextEntity diffContext) {
        List<String> notes = new ArrayList<>();
        notes.add("验证计划基于任务目标“" + value(task.getGoal(), "未提供") + "”和 diff 上下文生成。");
        notes.add("当前 diff 摘要：" + value(diffContext.getDiffSummary(), "无 diff 摘要"));
        notes.add("如果修复来自线上故障，还应补充可观测指标或日志断言作为上线观察项。");
        return notes;
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveRelatedTestFiles(EngineeringTaskEntity task,
                                                 RepoDiffContextEntity diffContext,
                                                 Map<String, Object> codeLocalization) {
        Set<String> tests = new LinkedHashSet<>(list(diffContext.getRelatedTestFiles()));
        Object relatedTests = codeLocalization.get("relatedTestFiles");
        if (relatedTests instanceof List<?> values) {
            values.stream().map(String::valueOf).filter(value -> !value.isBlank()).forEach(tests::add);
        }
        Object targetFiles = codeLocalization.get("targetFiles");
        if (targetFiles instanceof List<?> values) {
            for (Object value : values) {
                tests.addAll(findTestsForSource(task.getRepository(), String.valueOf(value)));
            }
        }
        return new ArrayList<>(tests);
    }

    private List<String> findTestsForSource(String repository, String sourceFile) {
        if (isBlank(sourceFile) || !sourceFile.endsWith(".java")) {
            return List.of();
        }
        Path repo = isBlank(repository) ? Path.of("").toAbsolutePath().normalize() : Path.of(repository).toAbsolutePath().normalize();
        String simpleName = sourceFile.replace('\\', '/');
        int slash = simpleName.lastIndexOf('/');
        if (slash >= 0) {
            simpleName = simpleName.substring(slash + 1);
        }
        simpleName = simpleName.substring(0, simpleName.length() - ".java".length());
        Set<String> expectedNames = Set.of(simpleName + "Test.java", simpleName + "Tests.java");
        List<String> matches = new ArrayList<>();
        Path testRoot = repo.resolve("src/test");
        if (!Files.exists(testRoot)) {
            testRoot = repo;
        }
        try (var paths = Files.walk(testRoot, 12)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> expectedNames.contains(path.getFileName().toString()))
                    .forEach(path -> matches.add(repo.relativize(path).toString().replace('\\', '/')));
        } catch (IOException ignored) {
            return List.of();
        }
        return matches;
    }

    private List<CodeSnippetEntity> loadTestSnippets(String repositoryPath, List<String> relatedTestFiles) {
        List<CodeSnippetEntity> snippets = new ArrayList<>();
        for (String testFile : list(relatedTestFiles)) {
            snippets.add(toolGateway.readFileSnippet(repositoryPath, testFile, 1, 80));
            if (snippets.size() >= 4) {
                break;
            }
        }
        return snippets;
    }

    private String buildPatchFromFileRewrites(String repositoryPath, List<FileRewritePatchEntity> rewrites) {
        if (rewrites == null || rewrites.isEmpty() || isBlank(repositoryPath)) {
            return "";
        }
        Path repo = Path.of(repositoryPath).toAbsolutePath().normalize();
        StringBuilder patch = new StringBuilder();
        for (FileRewritePatchEntity rewrite : rewrites) {
            if (rewrite == null || isBlank(rewrite.getFilePath()) || isBlank(rewrite.getNewContent())) {
                continue;
            }
            Path file = repo.resolve(rewrite.getFilePath()).normalize();
            if (!file.startsWith(repo)) {
                continue;
            }
            try {
                if (Files.exists(file) && Files.isRegularFile(file)) {
                    String oldContent = Files.readString(file, StandardCharsets.UTF_8);
                    patch.append(buildWholeFileDiff(rewrite.getFilePath(), oldContent, rewrite.getNewContent()));
                } else if (isTestFile(rewrite.getFilePath())) {
                    patch.append(buildNewFileDiff(rewrite.getFilePath(), rewrite.getNewContent()));
                }
            } catch (IOException ignored) {
                // Skip unreadable rewrite candidates.
            }
        }
        return patch.toString();
    }

    private String buildWholeFileDiff(String filePath, String oldContent, String newContent) {
        List<String> oldLines = splitContent(oldContent);
        List<String> newLines = splitContent(newContent);
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(filePath).append('\n');
        diff.append("+++ b/").append(filePath).append('\n');
        diff.append("@@ -1,").append(oldLines.size()).append(" +1,").append(newLines.size()).append(" @@\n");
        oldLines.forEach(line -> diff.append('-').append(line).append('\n'));
        newLines.forEach(line -> diff.append('+').append(line).append('\n'));
        return diff.toString();
    }

    private String buildNewFileDiff(String filePath, String newContent) {
        List<String> newLines = splitContent(newContent);
        StringBuilder diff = new StringBuilder();
        diff.append("--- /dev/null\n");
        diff.append("+++ b/").append(filePath).append('\n');
        diff.append("@@ -0,0 +1,").append(newLines.size()).append(" @@\n");
        newLines.forEach(line -> diff.append('+').append(line).append('\n'));
        return diff.toString();
    }

    private boolean isTestFile(String filePath) {
        String normalized = value(filePath, "").replace('\\', '/');
        return normalized.startsWith("src/test/") && normalized.endsWith(".java");
    }

    private String testClassName(String filePath) {
        String normalized = value(filePath, "").replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - ".java".length()) : fileName;
    }

    private record CommandFilterResult(String command, boolean skipped, List<String> removedTests) {
    }

    private List<String> splitContent(String content) {
        String normalized = value(content, "").replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private boolean shouldApplyTestPatch(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return false;
        }
        Object value = task.getContext().get("allowTestPatchApply");
        return value instanceof Boolean bool ? bool : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean isAsyncTestExecution(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) {
            return false;
        }
        Object value = task.getContext().get("asyncTestExecution");
        return value instanceof Boolean bool ? bool : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private List<String> list(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String join(List<String> values) {
        List<String> safeValues = list(values);
        return safeValues.isEmpty() ? "无" : String.join(", ", safeValues);
    }

    private String value(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String detectTestFailureType(String testResults) {
        if (isBlank(testResults)) return "";
        String lower = testResults.toLowerCase();
        if (lower.contains("compilation failure") || lower.contains("compilation error")
                || lower.contains("cannot find symbol") || lower.contains("does not exist")) {
            return "TEST_COMPILE_FAILED";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "TEST_TIMEOUT";
        }
        if (lower.contains("assertion") || (lower.contains("expected") && (lower.contains("actual") || lower.contains("but was")))
                || lower.contains("failures:") || lower.contains("<<< failure!")) {
            return "TEST_ASSERTION_FAILED";
        }
        if (lower.contains("patch does not apply") || lower.contains("context not found")) {
            return "TEST_PATCH_APPLY_FAILED";
        }
        return "UNKNOWN";
    }

    private List<String> extractFailedCommands(String testResults) {
        List<String> cmds = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("mvn\\s+[^\"]+").matcher(
                testResults == null ? "" : testResults);
        while (m.find()) {
            String cmd = m.group().trim();
            if (!cmds.contains(cmd)) cmds.add(cmd);
        }
        return cmds;
    }

    private List<String> extractFailedTestFiles(String testResults) {
        if (isBlank(testResults)) return List.of();
        List<String> files = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "([\\w]+\\.java:\\d+|[\\w]+Test\\.\\w+)").matcher(testResults);
        while (m.find()) {
            files.add(m.group(1));
        }
        return files.stream().distinct().limit(10).toList();
    }

    private List<String> extractFailedAssertions(String testResults) {
        if (isBlank(testResults)) return List.of();
        List<String> assertions = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(expected:\\s*<[^>]*>\\s*but was:\\s*<[^>]*>)").matcher(testResults);
        while (m.find()) {
            assertions.add(m.group(1));
        }
        return assertions.stream().distinct().limit(10).toList();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

}
