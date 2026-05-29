package com.opsautoagent.domain.codeops.agent.skill;

import com.opsautoagent.domain.codeops.agent.bugfix.CodeOpsBugFixAgentInput;
import com.opsautoagent.domain.codeops.agent.bugfix.CodeOpsBugFixAgentOutput;
import com.opsautoagent.domain.codeops.agent.bugfix.CodeOpsBugFixAgentService;
import com.opsautoagent.domain.codeops.agent.memory.IncidentMemoryService;
import com.opsautoagent.domain.codeops.agent.patch.PatchApplyResult;
import com.opsautoagent.domain.codeops.agent.security.AgentPermissionPolicy;
import com.opsautoagent.domain.codeops.agent.security.HumanApprovalGate;
import com.opsautoagent.domain.codeops.agent.patch.PatchApplyService;
import com.opsautoagent.domain.codeops.agent.patch.FileRewritePatchEntity;
import com.opsautoagent.domain.codeops.agent.patch.PatchScopeGuardResult;
import com.opsautoagent.domain.codeops.agent.patch.PatchScopeGuardService;
import com.opsautoagent.domain.codeops.agent.patch.PatchValidationResult;
import com.opsautoagent.domain.codeops.agent.patch.PatchValidationService;
import com.opsautoagent.domain.codeops.model.entity.EngineeringKnowledgeMatchEntity;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolGateway;
import com.opsautoagent.domain.codeops.model.entity.BugFixSuggestionEntity;
import com.opsautoagent.domain.codeops.model.entity.CodeSnippetEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffContextEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffHunkEntity;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BugFixSkill implements EngineeringSkill {

    public static final String SKILL_ID = "bug_fix";

    private final EngineeringToolGateway toolGateway;

    private final CodeOpsBugFixAgentService bugFixAgentService;

    private final PatchValidationService patchValidationService;

    private final PatchApplyService patchApplyService;

    private final PatchScopeGuardService patchScopeGuardService;

    private final IncidentMemoryService incidentMemoryService;
    private final AgentPermissionPolicy permissionPolicy;
    private final HumanApprovalGate humanApprovalGate;

    @Value("${codeops.bugfix.compile-timeout-ms:300000}")
    private long compileTimeoutMs;

    public BugFixSkill(EngineeringToolGateway toolGateway,
                       CodeOpsBugFixAgentService bugFixAgentService,
                       PatchValidationService patchValidationService,
                       PatchApplyService patchApplyService,
                       PatchScopeGuardService patchScopeGuardService,
                       IncidentMemoryService incidentMemoryService,
                       AgentPermissionPolicy permissionPolicy,
                       HumanApprovalGate humanApprovalGate) {
        this.toolGateway = toolGateway;
        this.bugFixAgentService = bugFixAgentService;
        this.patchValidationService = patchValidationService;
        this.patchApplyService = patchApplyService;
        this.patchScopeGuardService = patchScopeGuardService;
        this.incidentMemoryService = incidentMemoryService;
        this.permissionPolicy = permissionPolicy;
        this.humanApprovalGate = humanApprovalGate;
    }

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("Bug Fix Skill")
                .description("Analyze task goal and repository diff context, then produce localization clues, fix suggestions and a patch draft placeholder.")
                .supportedTaskTypes(List.of("ISSUE_TO_PATCH", "INCIDENT_TO_FIX", "BUG_FIX"))
                .requiredTools(List.of("repo.git_diff", "repo.find_tests", "repo.search_text"))
                .riskLevel("READ_ONLY")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        RepoDiffContextEntity diffContext = toolGateway.loadDiffContext(task.getRepository(), task.getChangeRef(), task.getContext());
        List<String> suspiciousLocations = buildSuspiciousLocations(task, diffContext);
        List<String> diagnosisClues = buildDiagnosisClues(task, diffContext);
        List<Object> reflectionDiagnostics = extractReflectionDiagnostics(task);
        diagnosisClues = appendReflectionDiagnosticClues(diagnosisClues, reflectionDiagnostics);
        suspiciousLocations = appendReflectionDiagnosticLocations(suspiciousLocations, reflectionDiagnostics);
        List<String> ruleSuggestions = buildFixSuggestions(task, diffContext);
        List<CodeSnippetEntity> codeSnippets = loadCodeSnippets(task);
        Map<String, Object> repairScope = buildRepairScope(task, suspiciousLocations, diagnosisClues);

        if ("NO_CODE_FIX".equals(repairScope.get("scopeType"))) {
            return EngineeringSkillResultEntity.builder()
                    .skillId(SKILL_ID)
                    .status("NO_DIFF")
                    .summary("Incident triage classified as " + repairScope.get("strategyType")
                            + " — no source code repair needed. Reason: " + repairScope.get("scopeReasoning"))
                    .evidence(List.of(
                            "策略类型：" + repairScope.get("strategyType"),
                            "作用域：" + repairScope.get("scopeType"),
                            "原因：" + repairScope.get("scopeReasoning")
                    ))
                    .nextActions(List.of("交给 Release Risk Skill 评估运行/配置/容量层面的处置建议"))
                    .rawOutput(Map.of(
                            "phase", "BUG_FIX_SKIPPED_NO_CODE_FIX",
                            "repairScope", repairScope,
                            "verdict", "No code patch needed — this is a runtime/config/capacity incident."
                    ))
                    .build();
        }

        // Recall similar incident patterns from multi-layer memory
        List<String> memoryKeywords = extractMemoryKeywords(task.getGoal(), diagnosisClues, suspiciousLocations);
        String memoryPrompt = incidentMemoryService.buildMemoryPrompt(memoryKeywords);
        List<Map<String, Object>> memoryHints = List.of();
        if (!memoryPrompt.isBlank()) {
            // Inject as additional diagnosis clue so LLM sees AVOID hints
            diagnosisClues = appendMemoryToClues(diagnosisClues, memoryPrompt);
        }

        CodeOpsBugFixAgentOutput llmFix = bugFixAgentService.proposeFix(CodeOpsBugFixAgentInput.builder()
                .taskId(task.getTaskId())
                .taskType(task.getTaskType())
                .goal(task.getGoal())
                .repositoryPath(value(diffContext.getRepositoryPath(), ""))
                .changeRef(value(diffContext.getChangeRef(), "working_tree"))
                .opsDiagnosis(extractOpsDiagnosis(task))
                .diagnosisClues(diagnosisClues)
                .suspiciousLocations(suspiciousLocations)
                .repairScope(repairScope)
                .memoryHints(memoryHints)
                .codeSearchMatches(extractCodeSearchMatches(task))
                .codeSnippets(codeSnippets)
                .knowledgeMatches(extractKnowledgeMatches(task))
                .reflectionFailures(extractReflectionFailures(task))
                .reflectionDiagnostics(reflectionDiagnostics)
                .build());
        String fallbackPatch = buildPatchDraft(task, diffContext, codeSnippets);
        String rewritePatch = buildPatchFromFileRewrites(value(diffContext.getRepositoryPath(), ""), llmFix.getFileRewrites());
        String llmPatch = !isBlank(rewritePatch) ? rewritePatch : value(llmFix.getUnifiedDiffPatch(), "");
        boolean llmPatchAvailable = llmFix.isSuccess() && !isBlank(llmPatch);
        boolean incidentToFix = "INCIDENT_TO_FIX".equals(task.getTaskType());
        BugFixSuggestionEntity suggestion = BugFixSuggestionEntity.builder()
                .repositoryPath(value(diffContext.getRepositoryPath(), ""))
                .changeRef(value(diffContext.getChangeRef(), "working_tree"))
                .changedFiles(list(diffContext.getChangedFiles()))
                .suspiciousLocations(suspiciousLocations)
                .diagnosisClues(diagnosisClues)
                .fixSuggestions(llmPatchAvailable ? llmFix.getReasoning() : ruleSuggestions)
                .codeSnippets(codeSnippets)
                .patchDraft(llmPatchAvailable ? llmPatch : (incidentToFix ? "" : fallbackPatch))
                .rootCause(value(llmFix.getRootCause(), "LLM 未确认具体根因"))
                .confidence(value(llmFix.getConfidence(), llmPatchAvailable ? "MEDIUM" : "LOW"))
                .llmGenerated(llmPatchAvailable)
                .llmErrorMessage(llmFix.getErrorMessage())
                .verificationHints(buildVerificationHints(diffContext))
                .build();

        PatchScopeGuardResult scopeGuard = patchScopeGuardService.validate(
                suggestion.getRepositoryPath(),
                suggestion.getPatchDraft(),
                llmFix.getFileRewrites(),
                repairScope);
        if (!scopeGuard.isPassed()) {
            return EngineeringSkillResultEntity.builder()
                    .skillId(SKILL_ID)
                    .status("FAILED")
                    .summary("PatchScopeGuard 拦截：补丁越界。failureType=" + scopeGuard.getFailureType()
                            + "，violations=" + scopeGuard.getViolations())
                    .evidence(List.of(
                            "修复范围：" + repairScope.get("scopeType"),
                            "允许的方法：" + repairScope.get("targetMethods"),
                            "实际改动的方法：" + scopeGuard.getChangedMethods(),
                            "违规：" + String.join("; ", scopeGuard.getViolations())
                    ))
                    .nextActions(List.of(
                            "检查 repairScope 定义是否准确",
                            "交给 reflection 轮让 LLM 重新生成范围内 patch",
                            "确认事故根因是否需要调整为 MULTI_METHOD 或 FULL_FILE"
                    ))
                    .rawOutput(buildRawOutput(suggestion, task,
                            PatchValidationResult.builder().patchPresent(false).valid(false).build(),
                            PatchApplyResult.skipped(suggestion.getRepositoryPath(), "Blocked by PatchScopeGuard"),
                            SourceValidationResult.skipped(),
                            null, false, llmFix, scopeGuard))
                    .build();
        }

        PatchValidationResult patchValidation = patchValidationService.validate(
                suggestion.getRepositoryPath(),
                suggestion.getPatchDraft());
        Map<String, String> sourceSnapshot = snapshotFiles(suggestion.getRepositoryPath(), patchValidation.getExistingTouchedFiles());
        List<String> newSourceFiles = missingFiles(suggestion.getRepositoryPath(), patchValidation.getTouchedFiles());
        PatchApplyResult patchApply = shouldApplyPatch(task)
                ? patchApplyService.apply(suggestion.getRepositoryPath(), suggestion.getPatchDraft())
                : PatchApplyResult.skipped(suggestion.getRepositoryPath(), "Patch apply disabled. Set task context allowPatchApply=true to modify repository files.");
        SourceValidationResult sourceValidation = patchApply.isApplied()
                ? validateTouchedJavaSources(suggestion.getRepositoryPath(), patchValidation.getTouchedFiles())
                : SourceValidationResult.skipped();
        EngineeringToolGateway.CommandResult compileGate = patchApply.isApplied() && sourceValidation.valid()
                ? runCompileGate(task)
                : null;
        boolean compileGatePassed = sourceValidation.valid() && (compileGate == null || compileGate.success());
        boolean patchRolledBack = false;
        if (patchApply.isApplied() && !compileGatePassed) {
            boolean restoredExisting = restoreFiles(suggestion.getRepositoryPath(), sourceSnapshot);
            boolean removedNew = deleteFiles(suggestion.getRepositoryPath(), newSourceFiles);
            patchRolledBack = restoredExisting || removedNew;
        }
        boolean fixReady = Boolean.TRUE.equals(suggestion.getLlmGenerated())
                && (!shouldApplyPatch(task) || (patchApply.isApplied() && compileGatePassed));
        String status = fixReady
                ? "SUCCESS"
                : (incidentToFix && !llmPatchAvailable ? "FAILED" : (patchApply.isApplied() && !compileGatePassed ? "FAILED" : "NO_DIFF"));

        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status(status)
                .summary("已生成 Bug 修复分析骨架：定位线索 "
                        + suggestion.getSuspiciousLocations().size()
                        + " 条，修复建议 "
                        + suggestion.getFixSuggestions().size()
                        + " 条，LLM patch="
                        + Boolean.TRUE.equals(suggestion.getLlmGenerated())
                        + "，patchApplied="
                        + patchApply.isApplied()
                        + "，compileGate="
                        + (compileGate == null ? "SKIPPED" : compileGate.success())
                        + "，patchRolledBack="
                        + patchRolledBack + "。")
                .evidence(List.of(
                        "任务目标：" + value(task.getGoal(), "未提供"),
                        "仓库：" + value(diffContext.getRepositoryPath(), ""),
                        "变更：" + value(diffContext.getDiffSummary(), "无 diff 摘要"),
                        "LLM 根因：" + value(suggestion.getRootCause(), "未确认"),
                        "可疑位置：" + join(suggestion.getSuspiciousLocations()),
                        "相关测试：" + join(diffContext.getRelatedTestFiles())
                ))
                .nextActions(List.of("结合 Issue/告警原文补充复现条件", "由 LLM/人工确认具体代码修改点", "交给 Test Verification Skill 生成验证计划"))
                .rawOutput(buildRawOutput(suggestion, task, patchValidation, patchApply, sourceValidation, compileGate, patchRolledBack, llmFix, scopeGuard))
                .build();
    }

    private Map<String, Object> buildRawOutput(BugFixSuggestionEntity suggestion,
                                                         EngineeringTaskEntity task,
                                                         PatchValidationResult patchValidation,
                                                         PatchApplyResult patchApply,
                                                         SourceValidationResult sourceValidation,
                                                         EngineeringToolGateway.CommandResult compileGate,
                                                         boolean patchRolledBack,
                                                         CodeOpsBugFixAgentOutput llmFix,
                                                         PatchScopeGuardResult scopeGuard) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("phase", "PHASE_5_BUG_FIX_PATCH_PROPOSAL");
        output.put("patchScopeGuard", scopeGuard == null ? Map.of() : scopeGuard.toRawOutput());
        output.put("repositoryPath", suggestion.getRepositoryPath());
        output.put("changeRef", suggestion.getChangeRef());
        output.put("changedFiles", suggestion.getChangedFiles());
        output.put("suspiciousLocations", suggestion.getSuspiciousLocations());
        output.put("diagnosisClues", suggestion.getDiagnosisClues());
        output.put("fixSuggestions", suggestion.getFixSuggestions());
        output.put("patchDraft", suggestion.getPatchDraft());
        output.put("rootCause", suggestion.getRootCause());
        output.put("confidence", suggestion.getConfidence());
        output.put("reflectionDiagnosis", llmFix == null || llmFix.getReflectionDiagnosis() == null ? Map.of() : llmFix.getReflectionDiagnosis());
        output.put("modelRouting", llmFix == null || llmFix.getModelRouting() == null ? Map.of() : llmFix.getModelRouting());
        // Permission policy
        AgentPermissionPolicy.PolicyDecision policy = permissionPolicy.evaluate(
                suggestion.getRepositoryPath(), task.getTaskType(), "MEDIUM");
        output.put("permissionPolicy", policy.toMap());
        output.put("llmGenerated", suggestion.getLlmGenerated());
        output.put("llmErrorMessage", suggestion.getLlmErrorMessage());
        output.put("testSuggestions", llmFix == null || llmFix.getTestSuggestions() == null ? List.of() : llmFix.getTestSuggestions());
        output.put("mavenCommands", llmFix == null || llmFix.getMavenCommands() == null ? List.of() : llmFix.getMavenCommands());
        output.put("testUnifiedDiffPatch", llmFix == null ? "" : value(llmFix.getTestUnifiedDiffPatch(), ""));
        output.put("testFileRewrites", llmFix == null || llmFix.getTestFileRewrites() == null ? List.of() : llmFix.getTestFileRewrites());
        output.put("codeSnippets", suggestion.getCodeSnippets());
        output.put("codeSearchMatches", extractCodeSearchMatches(task));
        output.put("verificationHints", suggestion.getVerificationHints());
        output.put("patchValidation", patchValidation.toRawOutput());
        output.put("patchApply", patchApply.toRawOutput());
        output.put("sourceValidation", sourceValidation.toRawOutput());
        output.put("compileGate", compileGate == null ? Map.of(
                "requested", false,
                "success", sourceValidation.valid(),
                "reason", patchApply.isApplied() ? "source validation failed before compile gate" : "patch not applied"
        ) : Map.of(
                "requested", true,
                "command", compileGate.command(),
                "success", compileGate.success(),
                "exitCode", compileGate.exitCode(),
                "costMillis", compileGate.costMillis(),
                "output", compileGate.output()
        ));
        output.put("patchRolledBack", patchRolledBack);
        output.put("patchRollbackReason", patchRolledBack
                ? "代码补丁已应用但编译失败，已回滚源码文件，失败日志会回灌给下一轮 Code Repair Agent。"
                : "");

        // Reflection-aware metadata
        if (task.getContext() != null) {
            Object round = task.getContext().get("incidentFixReflectionRound");
            output.put("reflectionRound", round instanceof Number ? ((Number) round).intValue() : 0);
            output.put("reflectionDiagnosticsUsed",
                    task.getContext().getOrDefault("incidentFixReflectionDiagnostics", List.of()));
        }
        return output;
    }

    private SourceValidationResult validateTouchedJavaSources(String repositoryPath, List<String> touchedFiles) {
        if (isBlank(repositoryPath)) {
            return SourceValidationResult.failed(List.of("repository path is blank"));
        }
        Path repo = Path.of(repositoryPath).toAbsolutePath().normalize();
        List<String> errors = new ArrayList<>();
        for (String filePath : list(touchedFiles)) {
            if (isBlank(filePath) || !filePath.endsWith(".java")) {
                continue;
            }
            Path file = repo.resolve(filePath).normalize();
            if (!file.startsWith(repo) || !Files.exists(file) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                int balance = braceBalance(content);
                if (balance != 0) {
                    errors.add(filePath + " has unbalanced braces: balance=" + balance);
                }
                if (hasNonWhitespaceAfterFinalClassBrace(content)) {
                    errors.add(filePath + " has non-whitespace content after final class brace");
                }
            } catch (IOException e) {
                errors.add(filePath + " cannot be read: " + e.getMessage());
            }
        }
        return errors.isEmpty() ? SourceValidationResult.passed() : SourceValidationResult.failed(errors);
    }

    private int braceBalance(String content) {
        int balance = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = 0; i < value(content, "").length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (!inChar && c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && c == '\'') {
                inChar = !inChar;
                continue;
            }
            if (inString || inChar) {
                continue;
            }
            if (c == '{') {
                balance++;
            } else if (c == '}') {
                balance--;
            }
        }
        return balance;
    }

    private boolean hasNonWhitespaceAfterFinalClassBrace(String content) {
        String safeContent = value(content, "");
        int index = safeContent.lastIndexOf('}');
        return index >= 0 && !safeContent.substring(index + 1).trim().isEmpty();
    }

    private EngineeringToolGateway.CommandResult runCompileGate(EngineeringTaskEntity task) {
        return toolGateway.runMavenCommand(task.getRepository(), List.of("-q", "-DskipTests", "compile"), compileTimeoutMs);
    }

    private boolean shouldApplyPatch(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return false;
        }
        Object value = task.getContext().get("allowPatchApply");
        return value instanceof Boolean bool ? bool : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private Map<String, String> snapshotFiles(String repositoryPath, List<String> files) {
        if (isBlank(repositoryPath) || files == null || files.isEmpty()) {
            return Map.of();
        }
        Path repoRoot = Path.of(repositoryPath).toAbsolutePath().normalize();
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String filePath : files) {
            if (isBlank(filePath)) {
                continue;
            }
            Path file = repoRoot.resolve(filePath).normalize();
            if (!file.startsWith(repoRoot) || !Files.exists(file) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                snapshot.put(filePath, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Snapshot is best effort. Compile/test failure remains visible if rollback cannot restore a file.
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
        Path repoRoot = Path.of(repositoryPath).toAbsolutePath().normalize();
        boolean restoredAny = false;
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            Path file = repoRoot.resolve(entry.getKey()).normalize();
            if (!file.startsWith(repoRoot)) {
                continue;
            }
            try {
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
                restoredAny = true;
            } catch (IOException ignored) {
                // Keep the compile failure visible for the next reflection round.
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
                // Keep the compile failure visible for the next reflection round.
            }
        }
        return deletedAny;
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
            if (!file.startsWith(repo) || !Files.exists(file) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                String oldContent = Files.readString(file, StandardCharsets.UTF_8);
                patch.append(buildWholeFileDiff(rewrite.getFilePath(), oldContent, rewrite.getNewContent()));
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

    private List<String> splitContent(String content) {
        String normalized = value(content, "").replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractOpsDiagnosis(EngineeringTaskEntity task) {
        Map<String, Object> empty = Map.of();
        if (task.getContext() == null) {
            return empty;
        }
        Object skillOutputs = task.getContext().get("skillOutputs");
        if (!(skillOutputs instanceof Map<?, ?> outputs)) {
            return empty;
        }
        Object opsOutput = outputs.get("ops_diagnosis");
        if (!(opsOutput instanceof Map<?, ?> opsMap)) {
            return empty;
        }
        Object opsDiagnosis = opsMap.get("opsDiagnosis");
        if (opsDiagnosis instanceof Map<?, ?> diagnosisMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            diagnosisMap.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return empty;
    }

    @SuppressWarnings("unchecked")
    private List<EngineeringKnowledgeMatchEntity> extractKnowledgeMatches(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return List.of();
        }
        Object skillOutputs = task.getContext().get("skillOutputs");
        if (!(skillOutputs instanceof Map<?, ?> outputs)) {
            return List.of();
        }
        Object knowledgeOutput = outputs.get("engineering_knowledge_rag");
        if (!(knowledgeOutput instanceof Map<?, ?> knowledgeMap)) {
            return List.of();
        }
        Object matches = knowledgeMap.get("matches");
        if (!(matches instanceof List<?> values)) {
            return List.of();
        }
        List<EngineeringKnowledgeMatchEntity> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof EngineeringKnowledgeMatchEntity match) {
                result.add(match);
            } else if (value instanceof Map<?, ?> map) {
                result.add(EngineeringKnowledgeMatchEntity.builder()
                        .documentId(String.valueOf(map.get("documentId")))
                        .title(String.valueOf(map.get("title")))
                        .category(String.valueOf(map.get("category")))
                        .score(parseInteger(map.get("score")))
                        .path(String.valueOf(map.get("path")))
                        .summary(String.valueOf(map.get("summary")))
                        .content(String.valueOf(map.get("content")))
                        .build());
            }
        }
        return result;
    }

    private List<String> buildSuspiciousLocations(EngineeringTaskEntity task, RepoDiffContextEntity diffContext) {
        List<String> locations = new ArrayList<>();
        locations.addAll(extractCodeSearchMatches(task));
        for (RepoDiffHunkEntity hunk : listHunks(diffContext)) {
            String filePath = value(hunk.getFilePath(), "unknown");
            Integer line = hunk.getNewStartLine();
            if (containsRiskKeyword(hunk.getSnippet())) {
                locations.add(filePath + ":" + (line == null ? 1 : line) + " 附近存在异常处理、事务、缓存、并发或远程调用相关变更。");
            }
        }
        if (locations.isEmpty()) {
            for (String file : list(diffContext.getChangedFiles())) {
                if (file.endsWith(".java")) {
                    locations.add(file + "：Java 业务代码发生变更，建议优先结合任务目标检查入口、Service 和 Repository 层。");
                }
            }
        }
        return locations.isEmpty() ? List.of("未发现可定位 diff，请先提供 changeRef 或工作区变更。") : locations;
    }

    private List<String> buildDiagnosisClues(EngineeringTaskEntity task, RepoDiffContextEntity diffContext) {
        List<String> clues = new ArrayList<>();
        clues.add("围绕任务目标排查：" + value(task.getGoal(), "未提供明确目标"));
        if (Boolean.TRUE.equals(diffContext.getDiffAvailable())) {
            clues.add("当前 diff 摘要：" + value(diffContext.getDiffSummary(), ""));
        }
        if (!list(diffContext.getRelatedTestFiles()).isEmpty()) {
            clues.add("已找到相关测试，可用于确认修复是否覆盖主路径：" + join(diffContext.getRelatedTestFiles()));
        } else {
            clues.add("尚未找到直接相关测试，修复前应先补充最小复现用例或回归用例。");
        }
        return clues;
    }

    private List<String> buildFixSuggestions(EngineeringTaskEntity task, RepoDiffContextEntity diffContext) {
        List<String> suggestions = new ArrayList<>();
        String goal = value(task.getGoal(), "").toLowerCase(Locale.ROOT);
        String diffText = value(diffContext.getDiffText(), "").toLowerCase(Locale.ROOT);
        if (goal.contains("null") || goal.contains("npe") || diffText.contains("null")) {
            suggestions.add("检查新增入参、外部返回值和配置读取的空值边界，避免只在调用点兜底而遗漏上游数据约束。");
        }
        if (goal.contains("timeout") || goal.contains("超时") || diffText.contains("timeout")) {
            suggestions.add("检查远程调用超时、重试和降级策略，确保异常不会被吞掉并保留可观测日志。");
        }
        if (diffText.contains("@transactional") || diffText.contains("transaction")) {
            suggestions.add("检查事务边界内是否包含远程调用、异步提交或缓存更新，必要时拆分事务后置动作。");
        }
        if (diffText.contains("cache") || diffText.contains("redis")) {
            suggestions.add("检查缓存更新与数据库写入的一致性，补充失败重试或失效策略。");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("先根据可疑位置补充复现测试，再做最小化修复，避免一次性重构扩大变更范围。");
            suggestions.add("优先保持接口契约兼容，修复后补充成功路径、异常路径和边界输入测试。");
        }
        return suggestions;
    }

    private String buildPatchDraft(EngineeringTaskEntity task, RepoDiffContextEntity diffContext, List<CodeSnippetEntity> snippets) {
        List<String> locations = extractCodeSearchMatches(task);
        String target = locations.isEmpty() ? firstJavaFile(diffContext) : locations.get(0);
        if (target == null) {
            return "PATCH_PROPOSAL_UNAVAILABLE: 未定位到 Java 文件或代码命中，无法生成可信补丁草稿。";
        }
        String file = target.contains(":") ? target.substring(0, target.indexOf(':')) : target;
        CodeSnippetEntity snippet = firstAvailableSnippet(snippets);
        if (snippet == null) {
            return buildFallbackPatchDraft(task, file, target);
        }
        String strategy = inferPatchStrategy(task, snippet);
        String anchorLine = findAnchorLine(snippet);
        String insertedCode = buildInsertedCode(strategy, task, anchorLine);
        return """
                PATCH_PROPOSAL_DRAFT
                --- a/%s
                +++ b/%s
                @@ around %s
                %s
                +%s

                // Patch rationale:
                // - strategy: %s
                // - suspicious location: %s
                // - incident goal: %s
                // - this is a proposal only; apply after adding a regression test.
                """.formatted(file, file,
                snippet.getFilePath() + ":" + snippet.getStartLine() + "-" + snippet.getEndLine(),
                anchorLine,
                insertedCode,
                strategy,
                target,
                value(task.getGoal(), "未提供"));
    }

    private String buildFallbackPatchDraft(EngineeringTaskEntity task, String file, String target) {
        return """
                PATCH_PROPOSAL_DRAFT
                --- a/%s
                +++ b/%s
                @@
                +// CodeOps could not read nearby source lines, but located: %s
                +// Add a focused regression test first, then apply the minimal guard/fallback around this location.
                +// Incident goal: %s
                """.formatted(file, file, target, value(task.getGoal(), "未提供"));
    }

    private List<CodeSnippetEntity> loadCodeSnippets(EngineeringTaskEntity task) {
        List<CodeSnippetEntity> snippets = new ArrayList<>();
        List<String> targetFiles = extractLocalizationTargetFiles(task);
        for (String targetFile : targetFiles) {
            String normalizedTargetFile = normalizeTargetFile(targetFile);
            if (!isBlank(normalizedTargetFile) && normalizedTargetFile.endsWith(".java")) {
                snippets.add(toolGateway.readFileSnippet(task.getRepository(), normalizedTargetFile, 1, 240));
            }
            if (snippets.size() >= 8) {
                return snippets;
            }
        }
        for (String dependencyFile : inferSiblingDependencyFiles(task.getRepository(), snippets)) {
            snippets.add(toolGateway.readFileSnippet(task.getRepository(), dependencyFile, 1, 180));
            if (snippets.size() >= 10) {
                return snippets;
            }
        }
        for (String testFile : findTestsForTargets(task.getRepository(), targetFiles)) {
            snippets.add(toolGateway.readFileSnippet(task.getRepository(), testFile, 1, 160));
            if (snippets.size() >= 11) {
                return snippets;
            }
        }
        CodeSnippetEntity pomSnippet = toolGateway.readFileSnippet(task.getRepository(), "pom.xml", 1, 180);
        if (Boolean.TRUE.equals(pomSnippet.getAvailable())) {
            snippets.add(pomSnippet);
        }
        for (String match : extractCodeSearchMatches(task)) {
            Location location = parseLocation(match);
            if (location != null) {
                snippets.add(toolGateway.readFileSnippet(task.getRepository(), location.filePath(), location.line(), 12));
            }
            if (snippets.size() >= 12) {
                break;
            }
        }
        return snippets;
    }

    private List<String> inferSiblingDependencyFiles(String repository, List<CodeSnippetEntity> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return List.of();
        }
        Set<String> files = new LinkedHashSet<>();
        Path repo = isBlank(repository) ? Path.of("").toAbsolutePath().normalize() : Path.of(repository).toAbsolutePath().normalize();
        for (CodeSnippetEntity snippet : snippets) {
            if (snippet == null || !Boolean.TRUE.equals(snippet.getAvailable()) || isBlank(snippet.getFilePath())) {
                continue;
            }
            String filePath = snippet.getFilePath().replace('\\', '/');
            int slash = filePath.lastIndexOf('/');
            if (slash < 0) {
                continue;
            }
            String packageDir = filePath.substring(0, slash + 1);
            for (String typeName : extractLikelyTypeNames(snippet.getLines())) {
                String dependencyFile = packageDir + typeName + ".java";
                Path dependencyPath = repo.resolve(dependencyFile).normalize();
                if (dependencyPath.startsWith(repo) && Files.exists(dependencyPath) && Files.isRegularFile(dependencyPath)) {
                    files.add(dependencyFile);
                }
            }
        }
        Set<String> existing = new LinkedHashSet<>();
        snippets.stream()
                .map(CodeSnippetEntity::getFilePath)
                .filter(path -> path != null)
                .map(path -> path.replace('\\', '/'))
                .forEach(existing::add);
        files.removeAll(existing);
        return new ArrayList<>(files);
    }

    private Set<String> extractLikelyTypeNames(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Set.of();
        }
        Set<String> types = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*(?:Service|Repository|Request|Response|Controller|Mapper|Client))\\b");
        for (String line : lines) {
            if (line == null || line.trim().startsWith("import ")) {
                continue;
            }
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                types.add(matcher.group(1));
            }
        }
        return types;
    }

    private List<String> findTestsForTargets(String repository, List<String> targetFiles) {
        if (targetFiles == null || targetFiles.isEmpty()) {
            return List.of();
        }
        Path repo = isBlank(repository) ? Path.of("").toAbsolutePath().normalize() : Path.of(repository).toAbsolutePath().normalize();
        Path testRoot = repo.resolve("src/test");
        if (!Files.exists(testRoot)) {
            return List.of();
        }
        Set<String> expectedNames = new LinkedHashSet<>();
        for (String targetFile : targetFiles) {
            String normalized = normalizeTargetFile(targetFile);
            if (isBlank(normalized) || !normalized.endsWith(".java")) {
                continue;
            }
            String fileName = normalized.replace('\\', '/');
            int slash = fileName.lastIndexOf('/');
            if (slash >= 0) {
                fileName = fileName.substring(slash + 1);
            }
            String simpleName = fileName.substring(0, fileName.length() - ".java".length());
            expectedNames.add(simpleName + "Test.java");
            expectedNames.add(simpleName + "Tests.java");
            if (simpleName.endsWith("Service")) {
                expectedNames.add(simpleName + "ConcurrencyTest.java");
            }
        }
        List<String> matches = new ArrayList<>();
        try (var paths = Files.walk(testRoot, 12)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> expectedNames.contains(path.getFileName().toString()))
                    .forEach(path -> matches.add(repo.relativize(path).toString().replace('\\', '/')));
        } catch (IOException ignored) {
            return List.of();
        }
        return matches;
    }

    private String normalizeTargetFile(String targetFile) {
        if (isBlank(targetFile)) {
            return "";
        }
        String normalized = targetFile.trim().replace('\\', '/');
        if (normalized.startsWith("src/")) {
            return normalized;
        }
        if (normalized.contains("/") || !normalized.endsWith(".java")) {
            return normalized;
        }
        if (normalized.contains(".")) {
            int javaIndex = normalized.lastIndexOf(".java");
            String withoutJavaSuffix = javaIndex >= 0 ? normalized.substring(0, javaIndex) : normalized;
            return "src/main/java/" + withoutJavaSuffix.replace('.', '/') + ".java";
        }
        return normalized;
    }

    private Location parseLocation(String match) {
        if (match == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("^(.+?):(\\d+)\\s").matcher(match);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new Location(matcher.group(1), Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private CodeSnippetEntity firstAvailableSnippet(List<CodeSnippetEntity> snippets) {
        if (snippets == null) {
            return null;
        }
        return snippets.stream()
                .filter(snippet -> Boolean.TRUE.equals(snippet.getAvailable()))
                .findFirst()
                .orElse(null);
    }

    private String inferPatchStrategy(EngineeringTaskEntity task, CodeSnippetEntity snippet) {
        String text = (value(task.getGoal(), "") + "\n" + join(snippet.getLines())).toLowerCase(Locale.ROOT);
        if (text.contains("null") || text.contains("nullpointerexception") || text.contains("npe")) {
            return "NULL_GUARD";
        }
        if (text.contains("timeout") || text.contains("超时") || text.contains("resttemplate") || text.contains("webclient") || text.contains("feign")) {
            return "DEPENDENCY_TIMEOUT_GUARD";
        }
        if (text.contains("duplicate") || text.contains("duplicated") || text.contains("重复") || text.contains("幂等")) {
            return "IDEMPOTENCY_GUARD";
        }
        if (text.contains("exception") || text.contains("5xx") || text.contains("500")) {
            return "EXCEPTION_MAPPING_AND_LOGGING";
        }
        return "MINIMAL_GUARD_AND_OBSERVABILITY";
    }

    private String findAnchorLine(CodeSnippetEntity snippet) {
        if (snippet.getLines() == null || snippet.getLines().isEmpty()) {
            return " // no source line available";
        }
        for (String line : snippet.getLines()) {
            String code = line.substring(line.indexOf(':') + 1).trim();
            if (code.contains("return ") || code.contains(".") || code.contains("(")) {
                return " " + code;
            }
        }
        return " " + snippet.getLines().get(0).substring(snippet.getLines().get(0).indexOf(':') + 1).trim();
    }

    private String buildInsertedCode(String strategy, EngineeringTaskEntity task, String anchorLine) {
        String indent = anchorLine.startsWith("        ") ? "        " : "        ";
        return switch (strategy) {
            case "NULL_GUARD" -> indent + "if (/* TODO: replace with the nullable value observed in incident */ == null) {\n"
                    + indent + "    throw new IllegalArgumentException(\"invalid request: required value is null\");\n"
                    + indent + "}";
            case "DEPENDENCY_TIMEOUT_GUARD" -> indent + "try {\n"
                    + indent + "    // TODO: move the dependency call into this guarded block and keep the original call semantics.\n"
                    + indent + "} catch (RuntimeException ex) {\n"
                    + indent + "    // TODO: map timeout/dependency failure to a controlled business exception and preserve structured logs.\n"
                    + indent + "    throw ex;\n"
                    + indent + "}";
            case "IDEMPOTENCY_GUARD" -> indent + "// TODO: check idempotency key / unique business key before creating or submitting duplicate data.";
            case "EXCEPTION_MAPPING_AND_LOGGING" -> indent + "// TODO: catch the specific incident exception, add structured context, and map it to a controlled error response.";
            default -> indent + "// TODO: add the minimal guard for the incident condition and keep the original behavior for normal requests.";
        };
    }

    private String firstJavaFile(RepoDiffContextEntity diffContext) {
        for (String file : list(diffContext.getChangedFiles())) {
            if (file.endsWith(".java") && !file.contains("/src/test/")) {
                return file;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCodeSearchMatches(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return List.of();
        }
        Object skillOutputs = task.getContext().get("skillOutputs");
        if (!(skillOutputs instanceof Map<?, ?> outputs)) {
            return List.of();
        }
        Object repoOutput = outputs.get("repo_understanding");
        if (!(repoOutput instanceof Map<?, ?> repoMap)) {
            return List.of();
        }
        Object matches = repoMap.get("codeSearchMatches");
        if (matches instanceof List<?> values) {
            return values.stream().map(String::valueOf).limit(20).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractLocalizationTargetFiles(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return List.of();
        }
        Object skillOutputs = task.getContext().get("skillOutputs");
        if (!(skillOutputs instanceof Map<?, ?> outputs)) {
            return List.of();
        }
        Object repoOutput = outputs.get("repo_understanding");
        if (!(repoOutput instanceof Map<?, ?> repoMap)) {
            return List.of();
        }
        Object targetFiles = repoMap.get("targetFiles");
        if (targetFiles instanceof List<?> values) {
            return values.stream()
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .limit(8)
                    .toList();
        }
        return List.of();
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

    private List<Object> extractReflectionDiagnostics(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return List.of();
        }
        Object diagnostics = task.getContext().get("incidentFixReflectionDiagnostics");
        if (diagnostics instanceof List<?> values) {
            return new ArrayList<>(values);
        }
        return List.of();
    }

    private List<String> appendReflectionDiagnosticClues(List<String> diagnosisClues, List<Object> reflectionDiagnostics) {
        List<String> clues = new ArrayList<>(diagnosisClues == null ? List.of() : diagnosisClues);
        for (Object diagnostic : reflectionDiagnostics == null ? List.of() : reflectionDiagnostics) {
            if (diagnostic instanceof Map<?, ?> map) {
                clues.add("REFLECTION_DIAGNOSTIC failureType=" + value(map.get("failureType"))
                        + ", failedFiles=" + value(map.get("failedFiles"))
                        + ", mustFix=" + value(map.get("mustFix"))
                        + ", mustAvoid=" + value(map.get("mustAvoid")));
            }
        }
        return clues;
    }

    private List<String> appendReflectionDiagnosticLocations(List<String> suspiciousLocations, List<Object> reflectionDiagnostics) {
        List<String> locations = new ArrayList<>(suspiciousLocations == null ? List.of() : suspiciousLocations);
        for (Object diagnostic : reflectionDiagnostics == null ? List.of() : reflectionDiagnostics) {
            if (!(diagnostic instanceof Map<?, ?> map)) {
                continue;
            }
            Object files = map.get("failedFiles");
            if (files instanceof List<?> values) {
                values.stream()
                        .map(String::valueOf)
                        .filter(value -> !value.isBlank())
                        .forEach(file -> locations.add(file + ":1 reflection failure target"));
            }
        }
        return locations;
    }

    private List<String> buildVerificationHints(RepoDiffContextEntity diffContext) {
        List<String> hints = new ArrayList<>();
        if (!list(diffContext.getRelatedTestFiles()).isEmpty()) {
            hints.add("优先运行相关测试：" + join(diffContext.getRelatedTestFiles()));
        }
        hints.add("运行 mvn -q -DskipTests compile 确认编译不破坏。");
        hints.add("修复完成后交给 TestVerificationSkill 生成 Maven 验证命令和覆盖缺口。");
        return hints;
    }

    private boolean containsRiskKeyword(String text) {
        String normalized = value(text, "").toLowerCase(Locale.ROOT);
        return normalized.contains("exception")
                || normalized.contains("@transactional")
                || normalized.contains("transaction")
                || normalized.contains("cache")
                || normalized.contains("redis")
                || normalized.contains("thread")
                || normalized.contains("async")
                || normalized.contains("timeout")
                || normalized.contains("http")
                || normalized.contains("resttemplate")
                || normalized.contains("webclient");
    }

    private List<RepoDiffHunkEntity> listHunks(RepoDiffContextEntity diffContext) {
        return diffContext.getHunks() == null ? List.of() : diffContext.getHunks();
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

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> buildRepairScope(EngineeringTaskEntity task,
                                                   List<String> suspiciousLocations,
                                                   List<String> diagnosisClues) {
        Map<String, Object> scope = new LinkedHashMap<>();

        List<String> rawMethods = new ArrayList<>();
        List<String> rawFiles = new ArrayList<>();
        String localizationConfidence = "MEDIUM";
        String strategyType = "CODE_FIX";

        // --- Phase 1: Read from CodeLocalization (LLM-driven, most reliable) ---
        Map<String, Object> codeLocalization = extractCodeLocalization(task);
        if (codeLocalization != null && !codeLocalization.isEmpty()) {
            Object tm = codeLocalization.get("targetMethods");
            if (tm instanceof List<?> list) {
                list.stream().map(String::valueOf).filter(v -> !v.isBlank()).forEach(rawMethods::add);
            }
            Object tf = codeLocalization.get("targetFiles");
            if (tf instanceof List<?> list) {
                list.stream().map(String::valueOf).filter(v -> !v.isBlank()).forEach(rawFiles::add);
            }
            Object lc = codeLocalization.get("localizationConfidence");
            if (lc != null) {
                localizationConfidence = String.valueOf(lc);
            }
            Object st = codeLocalization.get("strategyType");
            if (st != null) {
                strategyType = String.valueOf(st);
            }
            Object secr = codeLocalization.get("shouldEnterCodeRepair");
            if (Boolean.FALSE.equals(secr)) {
                strategyType = "NO_CODE_FIX";
            }
        }

        // --- Phase 2: Read from FixStrategy (triage classification) ---
        Map<String, Object> fixStrategy = extractFixStrategy(task);
        if (fixStrategy != null && !fixStrategy.isEmpty()) {
            Object st = fixStrategy.get("strategyType");
            if (st != null) {
                strategyType = String.valueOf(st);
            }
            Object secr = fixStrategy.get("shouldEnterCodeRepair");
            if (Boolean.FALSE.equals(secr)) {
                strategyType = "NO_CODE_FIX";
            }
        }

        // --- Phase 3: Fallback — regex extract from suspiciousLocations ---
        if (rawMethods.isEmpty()) {
            java.util.regex.Pattern methodPattern = java.util.regex.Pattern
                    .compile("\\.([a-zA-Z_][a-zA-Z0-9_]*)\\(.*\\)");
            for (String location : list(suspiciousLocations)) {
                java.util.regex.Matcher m = methodPattern.matcher(location);
                if (m.find()) {
                    String name = m.group(1);
                    if (!rawMethods.contains(name)) {
                        rawMethods.add(name);
                    }
                }
            }
        }
        if (rawMethods.isEmpty()) {
            java.util.regex.Pattern methodPattern = java.util.regex.Pattern
                    .compile("\\.([a-zA-Z_][a-zA-Z0-9_]*)\\(.*\\)");
            for (String clue : list(diagnosisClues)) {
                java.util.regex.Matcher m = methodPattern.matcher(clue);
                if (m.find()) {
                    rawMethods.add(m.group(1));
                    break;
                }
            }
        }

        if (rawFiles.isEmpty()) {
            for (String location : list(suspiciousLocations)) {
                String file = extractFilePath(location);
                if (file != null && !rawFiles.contains(file)) {
                    rawFiles.add(file);
                }
            }
        }

        // --- Phase 4: Qualify method names and build file-method mapping ---
        List<String> qualifiedMethods = new ArrayList<>();
        Map<String, List<String>> fileMethodMap = new LinkedHashMap<>();

        // Determine which files are "primary" — from CodeLocalization or the first non-controller file
        Set<String> primaryFiles = new LinkedHashSet<>(rawFiles);
        if (primaryFiles.isEmpty()) {
            for (String location : list(suspiciousLocations)) {
                String file = extractFilePath(location);
                if (file != null && !file.contains("Controller")) {
                    primaryFiles.add(normalizeFilePath(file));
                }
            }
        }

        // Build file-to-methods mapping: only include files in the primary set
        for (String location : list(suspiciousLocations)) {
            String file = extractFilePath(location);
            String method = extractMethodFromLocation(location);
            if (file != null && method != null) {
                String normFile = normalizeFilePath(file);
                boolean isPrimary = primaryFiles.stream()
                        .anyMatch(pf -> normFile.equals(pf) || normFile.endsWith("/" + pf) || pf.endsWith("/" + normFile));
                if (!isPrimary) {
                    continue; // skip methods from non-primary files (e.g., Controllers)
                }
                fileMethodMap.computeIfAbsent(normFile, k -> new ArrayList<>());
                List<String> methods = fileMethodMap.get(normFile);
                if (!methods.contains(method)) {
                    methods.add(method);
                }
            }
        }

        // Augment with CodeLocalization data: match qualified methods to files by class name
        Set<String> usedMethods = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : fileMethodMap.entrySet()) {
            String file = entry.getKey();
            String className = simpleClassName(file);
            for (String m : entry.getValue()) {
                String qualified = className.isEmpty() ? m : className + "." + m;
                qualifiedMethods.add(qualified);
                usedMethods.add(m);
            }
        }

        // Add remaining rawMethods that weren't matched to any file
        for (String rawMethod : rawMethods) {
            if (!usedMethods.contains(rawMethod)) {
                if (rawMethod.contains(".")) {
                    // Already qualified — extract class name and try to match to a file
                    String[] parts = rawMethod.split("\\.");
                    String className = parts.length >= 2 ? parts[parts.length - 2] : "";
                    String simpleMethod = parts[parts.length - 1];
                    boolean matched = false;
                    for (String rawFile : rawFiles) {
                        if (simpleClassName(rawFile).equals(className)) {
                            String normFile = normalizeFilePath(rawFile);
                            fileMethodMap.computeIfAbsent(normFile, k -> new ArrayList<>());
                            if (!fileMethodMap.get(normFile).contains(simpleMethod)) {
                                fileMethodMap.get(normFile).add(simpleMethod);
                            }
                            qualifiedMethods.add(rawMethod);
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        qualifiedMethods.add(rawMethod);
                    }
                } else {
                    qualifiedMethods.add(rawMethod);
                }
            }
        }

        // Fallback: if fileMethodMap is still empty, build it from rawFiles and rawMethods
        if (fileMethodMap.isEmpty() && !rawFiles.isEmpty() && !rawMethods.isEmpty()) {
            for (String rawFile : rawFiles) {
                String normFile = normalizeFilePath(rawFile);
                fileMethodMap.put(normFile, new ArrayList<>(rawMethods));
            }
            for (String rawFile : rawFiles) {
                String className = simpleClassName(rawFile);
                for (String rawMethod : rawMethods) {
                    String qualified = className.isEmpty() ? rawMethod : className + "." + rawMethod;
                    if (!qualifiedMethods.contains(qualified)) {
                        qualifiedMethods.add(qualified);
                    }
                }
            }
        }

        // If no files extracted but we have methods, qualify with what we can
        if (rawFiles.isEmpty() && !rawMethods.isEmpty()) {
            for (String rawMethod : rawMethods) {
                if (!qualifiedMethods.contains(rawMethod)) {
                    qualifiedMethods.add(rawMethod);
                }
            }
        }

        // --- Phase 5: Deduplicate qualifiedMethods by short name, prefer qualified form ---
        Map<String, String> deduped = new LinkedHashMap<>(); // shortName -> best qualified form
        for (String method : qualifiedMethods) {
            String shortName = method.contains(".") ? method.substring(method.lastIndexOf('.') + 1) : method;
            String existing = deduped.get(shortName);
            if (existing == null || (method.contains(".") && !existing.contains("."))) {
                deduped.put(shortName, method); // prefer the qualified form
            }
        }
        qualifiedMethods = new ArrayList<>(deduped.values());

        // --- Phase 6: Split methods into allowed (evidence-backed) vs candidate (scanned but not backed) ---
        List<String> allowedMethods = new ArrayList<>();
        List<String> candidateMethods = new ArrayList<>();
        List<String> ignoredMethods = new ArrayList<>();

        for (String method : qualifiedMethods) {
            if (isMethodInDiagnosis(method, diagnosisClues) || isMethodInDiagnosis(method, suspiciousLocations)) {
                allowedMethods.add(method);
            } else {
                candidateMethods.add(method);
            }
        }
        // If nothing has evidence backing, treat all as candidates (nothing auto-allowed)
        if (allowedMethods.isEmpty() && !candidateMethods.isEmpty()) {
            // Fallback: take the first candidate as allowed (most likely from the primary file)
            allowedMethods.add(candidateMethods.remove(0));
            ignoredMethods.addAll(candidateMethods);
            candidateMethods.clear();
        }

        // Build allowed fileMethodMap — only include files/methods present in allowedMethods
        Map<String, List<String>> allowedFileMethodMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : fileMethodMap.entrySet()) {
            String file = entry.getKey();
            String className = simpleClassName(file);
            List<String> fileAllowed = new ArrayList<>();
            for (String m : entry.getValue()) {
                String qualified = className.isEmpty() ? m : className + "." + m;
                if (allowedMethods.contains(qualified) || allowedMethods.contains(m)) {
                    fileAllowed.add(m);
                }
            }
            if (!fileAllowed.isEmpty()) {
                allowedFileMethodMap.put(file, fileAllowed);
            }
        }

        // --- Phase 7: Determine scopeType and scopeConfidence ---
        String scopeType;
        String scopeConfidence;
        StringBuilder reasoning = new StringBuilder();

        boolean hasExceptionInDiagnosis = diagnosisClues.stream()
                .anyMatch(c -> c.toLowerCase().contains("npe") || c.toLowerCase().contains("nullpointer")
                        || c.toLowerCase().contains("exception") || c.toLowerCase().contains("stack"));
        boolean singleFileInvolved = rawFiles.size() <= 1;

        if ("NO_CODE_FIX".equals(strategyType) || "CONFIG_FIX".equals(strategyType)
                || "RUNTIME_ACTION".equals(strategyType) || "CAPACITY_FIX".equals(strategyType)) {
            scopeType = "NO_CODE_FIX";
            scopeConfidence = "HIGH";
            allowedMethods.clear();
            allowedFileMethodMap.clear();
            reasoning.append("FixStrategy classified as ").append(strategyType)
                    .append(" — no source code repair needed. ");
        } else if (allowedMethods.size() <= 1 && singleFileInvolved && hasExceptionInDiagnosis) {
            scopeType = "STRICT_SINGLE_METHOD";
            scopeConfidence = "HIGH";
            String target = allowedMethods.isEmpty() ? "unknown" : allowedMethods.get(0);
            reasoning.append("Single target method ").append(target)
                    .append(" backed by exception/stack evidence in diagnosis. Only this method needs repair. ");
        } else if (allowedMethods.size() <= 1 && singleFileInvolved) {
            scopeType = "STRICT_SINGLE_METHOD";
            scopeConfidence = "MEDIUM";
            String target = allowedMethods.isEmpty() ? "unknown" : allowedMethods.get(0);
            reasoning.append("Single target method ").append(target)
                    .append(" with MEDIUM evidence confidence. Repair only this method. ");
        } else if (allowedMethods.size() <= 1) {
            scopeType = "STRICT_SINGLE_METHOD";
            scopeConfidence = "LOW";
            String target = allowedMethods.isEmpty() ? "unknown" : allowedMethods.get(0);
            reasoning.append("Single target method ").append(target)
                    .append(" with LOW evidence confidence. Repair only this method with caution. ");
        } else if (allowedMethods.size() <= 3) {
            scopeType = "MULTI_METHOD";
            scopeConfidence = "MEDIUM";
            reasoning.append("Multiple methods implicated (").append(allowedMethods.size())
                    .append(" with evidence backing): ").append(String.join(", ", allowedMethods))
                    .append(". Fix all listed methods and necessary signature adjustments. ");
        } else {
            scopeType = "FULL_FILE";
            scopeConfidence = "LOW";
            reasoning.append("Broad incident with ").append(allowedMethods.size())
                    .append(" allowed methods across ").append(rawFiles.size())
                    .append(" files. Full file scope but prefer minimal changes. ");
        }

        if (!candidateMethods.isEmpty()) {
            reasoning.append(" Candidate methods (insufficient evidence, NOT allowed to modify): ")
                    .append(String.join(", ", candidateMethods)).append(". ");
        }

        scope.put("scopeType", scopeType);
        scope.put("targetMethods", allowedMethods);           // ← Guard checks THIS
        scope.put("targetFiles", rawFiles.stream().map(this::normalizeFilePath).toList());
        scope.put("fileMethodMapping", allowedFileMethodMap); // ← Only allowed entries
        scope.put("candidateMethods", candidateMethods);      // ← Informational, not in guard scope
        scope.put("ignoredMethods", ignoredMethods);
        scope.put("scopeConfidence", scopeConfidence);
        scope.put("scopeReasoning", reasoning.toString());
        scope.put("localizationConfidence", localizationConfidence);
        scope.put("strategyType", strategyType);
        return scope;
    }

    private String simpleClassName(String filePath) {
        if (isBlank(filePath)) {
            return "";
        }
        String name = filePath.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        if (name.endsWith(".java")) {
            name = name.substring(0, name.length() - 5);
        }
        return name;
    }

    private String normalizeFilePath(String filePath) {
        if (isBlank(filePath)) {
            return "";
        }
        String normalized = filePath.replace('\\', '/');
        int srcIdx = normalized.indexOf("src/");
        return srcIdx >= 0 ? normalized.substring(srcIdx) : normalized;
    }

    private String extractFilePath(String location) {
        String cleaned = location.replaceAll("\\(.*$", "").trim();
        int javaIdx = cleaned.lastIndexOf(".java");
        if (javaIdx < 0) {
            return null;
        }
        return cleaned.substring(0, javaIdx + 5);
    }

    private List<String> extractMemoryKeywords(String goal, List<String> diagnosisClues, List<String> suspiciousLocations) {
        List<String> keywords = new ArrayList<>();
        String text = (goal == null ? "" : goal) + " " + String.join(" ", list(diagnosisClues))
                + " " + String.join(" ", list(suspiciousLocations));
        String lower = text.toLowerCase();

        // 1. Static technical terms
        for (String term : List.of("NullPointerException", "NPE", "IllegalArgumentException",
                "concurrency", "race", "deadlock", "idempotent", "timeout",
                "connection pool", "Hikari", "GC", "heap", "synchronized",
                "InventoryService", "OrderSubmitService", "IdempotencyService")) {
            if (lower.contains(term.toLowerCase())) keywords.add(term);
        }

        // 2. Extract exception class names dynamically: "java.lang.NullPointerException" → "NullPointerException"
        java.util.regex.Matcher exMatcher = java.util.regex.Pattern
                .compile("(\\w+Exception|\\w+Error)(?:\\.java)?")
                .matcher(text);
        while (exMatcher.find()) {
            String ex = exMatcher.group(1);
            if (!keywords.contains(ex)) keywords.add(ex);
        }

        // 3. Extract class names from suspiciousLocations: "OrderSubmitService.submit(...)" → "OrderSubmitService"
        java.util.regex.Matcher clsMatcher = java.util.regex.Pattern
                .compile("([A-Z][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\(")
                .matcher(text);
        while (clsMatcher.find()) {
            String cls = clsMatcher.group(1);
            String method = clsMatcher.group(2);
            if (!keywords.contains(cls)) keywords.add(cls);
            if (!keywords.contains(method)) keywords.add(method);
        }

        return keywords;
    }

    private List<String> appendMemoryToClues(List<String> diagnosisClues, String memoryPrompt) {
        List<String> clues = new ArrayList<>(diagnosisClues == null ? List.of() : diagnosisClues);
        clues.add("=== INCIDENT MEMORY RECALL ===");
        clues.add(memoryPrompt);
        clues.add("=== END MEMORY RECALL ===");
        return clues;
    }

    private String extractMethodFromLocation(String location) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\.([a-zA-Z_][a-zA-Z0-9_]*)\\(.*\\)");
        java.util.regex.Matcher m = p.matcher(location);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private boolean isMethodInDiagnosis(String qualifiedMethod, List<String> cluesOrLocations) {
        if (isBlank(qualifiedMethod)) {
            return false;
        }
        String shortName = qualifiedMethod.contains(".")
                ? qualifiedMethod.substring(qualifiedMethod.lastIndexOf('.') + 1)
                : qualifiedMethod;
        String className = qualifiedMethod.contains(".")
                ? qualifiedMethod.substring(0, qualifiedMethod.lastIndexOf('.'))
                : "";
        for (String clue : list(cluesOrLocations)) {
            String lower = clue.toLowerCase();
            if (lower.contains(shortName.toLowerCase())
                    && (!lower.contains("reflection") && !lower.contains("search") && !lower.contains("match"))) {
                return true;
            }
            if (!className.isEmpty() && lower.contains(className.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractCodeLocalization(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return null;
        }
        Object skillOutputs = task.getContext().get("skillOutputs");
        if (!(skillOutputs instanceof Map<?, ?> outputs)) {
            return null;
        }
        Object repoOutput = outputs.get("repo_understanding");
        if (!(repoOutput instanceof Map<?, ?> repoMap)) {
            return null;
        }
        Object codeLoc = repoMap.get("codeLocalization");
        if (codeLoc instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        repoMap.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFixStrategy(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return null;
        }
        Object skillOutputs = task.getContext().get("skillOutputs");
        if (!(skillOutputs instanceof Map<?, ?> outputs)) {
            return null;
        }
        Object strategy = outputs.get("fix_strategy_router");
        if (strategy instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return null;
    }

    private record Location(String filePath, int line) {
    }

    private record SourceValidationResult(boolean valid, List<String> errors) {

        static SourceValidationResult passed() {
            return new SourceValidationResult(true, List.of());
        }

        static SourceValidationResult skipped() {
            return new SourceValidationResult(true, List.of());
        }

        static SourceValidationResult failed(List<String> errors) {
            return new SourceValidationResult(false, errors == null ? List.of() : errors);
        }

        Map<String, Object> toRawOutput() {
            return Map.of(
                    "valid", valid,
                    "errors", errors == null ? List.of() : errors
            );
        }
    }

}
