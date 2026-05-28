package com.opsautoagent.domain.codeops.service;

import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolGateway;
import com.opsautoagent.domain.codeops.agent.skill.EngineeringSkill;
import com.opsautoagent.domain.codeops.agent.skill.EngineeringSkillRegistry;
import com.opsautoagent.domain.codeops.agent.orchestrator.IncidentFixOrchestratorDecision;
import com.opsautoagent.domain.codeops.agent.orchestrator.IncidentFixOrchestratorPolicy;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskStepEntity;
import com.opsautoagent.domain.codeops.model.entity.IncidentFixWorkingMemory;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class EngineeringTaskAgentService {

    private static final int MAX_REFLECTION_ROUNDS = 3;

    private final EngineeringSkillRegistry skillRegistry;

    private final IncidentFixOrchestratorPolicy orchestratorPolicy;

    private final EngineeringToolGateway toolGateway;

    private final ConcurrentMap<String, EngineeringTaskEntity> taskStore = new ConcurrentHashMap<>();

    public EngineeringTaskAgentService(EngineeringSkillRegistry skillRegistry,
                                       IncidentFixOrchestratorPolicy orchestratorPolicy,
                                       EngineeringToolGateway toolGateway) {
        this.skillRegistry = skillRegistry;
        this.orchestratorPolicy = orchestratorPolicy;
        this.toolGateway = toolGateway;
    }

    public EngineeringTaskEntity submit(EngineeringTaskEntity request) {
        Map<String, Object> initialContext = new LinkedHashMap<>();
        if (request.getContext() != null) {
            initialContext.putAll(request.getContext());
        }
        String repository = request.getRepository();
        if ((repository == null || repository.trim().isEmpty()) && initialContext.get("repository") != null) {
            repository = String.valueOf(initialContext.get("repository"));
        }
        initialContext.put("repoBaselineSnapshot", toolGateway.createRepositorySnapshot(repository));
        EngineeringTaskEntity task = EngineeringTaskEntity.builder()
                .taskId(UUID.randomUUID().toString())
                .taskType(normalizeTaskType(request.getTaskType()))
                .goal(request.getGoal())
                .repository(repository)
                .changeRef(request.getChangeRef())
                .focusAreas(request.getFocusAreas())
                .context(initialContext)
                .status("RUNNING")
                .maxRounds(resolveMaxRounds(request))
                .maxToolCalls(request.getMaxToolCalls() == null ? 20 : request.getMaxToolCalls())
                .usedToolCalls(0)
                .steps(new ArrayList<>())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        attachWorkingMemory(task);
        taskStore.put(task.getTaskId(), task);

        log.info("CodeOps task started. taskId={}, taskType={}, goal={}",
                task.getTaskId(), task.getTaskType(), task.getGoal());
        runTask(task);
        return task;
    }

    public EngineeringTaskEntity query(String taskId) {
        return taskStore.get(taskId);
    }

    public List<EngineeringTaskEntity> listRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return taskStore.values().stream()
                .sorted(Comparator.comparing(EngineeringTaskEntity::getCreateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .toList();
    }

    public List<EngineeringSkillEntity> listSkills() {
        return skillRegistry.listMetadata();
    }

    private void runTask(EngineeringTaskEntity task) {
        String stopReason = "";
        boolean stoppedByDecision = false;
        for (int stepNo = 1; stepNo <= task.getMaxRounds(); stepNo++) {
            if (isToolBudgetExhausted(task)) {
                stopReason = "达到最大工具调用预算，任务停止。";
                addStopStep(task, stepNo, stopReason);
                stoppedByDecision = true;
                break;
            }
            IncidentFixWorkingMemory workingMemory = getOrCreateWorkingMemory(task);
            IncidentFixOrchestratorDecision decision = orchestratorPolicy.decide(task, workingMemory);
            if (decision.shouldStop()) {
                stopReason = decision.getReason();
                addStopStep(task, stepNo, stopReason);
                stoppedByDecision = true;
                break;
            }
            executeSkill(task, stepNo, decision);
        }
        if (!stoppedByDecision && "RUNNING".equals(task.getStatus())) {
            stopReason = "达到最大执行轮数，任务停止。";
            addStopStep(task, (task.getSteps() == null ? 0 : task.getSteps().size()) + 1, stopReason);
        }
        task.setStatus(isFailureStop(task, stopReason) ? "FAILED" : "COMPLETED");
        task.setFinalSummary(buildFinalSummary(task));
        task.setUpdateTime(LocalDateTime.now());
    }

    private void executeSkill(EngineeringTaskEntity task, int stepNo, IncidentFixOrchestratorDecision decision) {
        String skillId = decision.getSelectedSkill();
        Optional<EngineeringSkill> optionalSkill = skillRegistry.find(skillId);
        if (optionalSkill.isEmpty()) {
            task.addStep(EngineeringTaskStepEntity.builder()
                    .stepNo(stepNo)
                    .decision(decision.getDecision())
                    .selectedSkill(skillId)
                    .reason(decision.getReason() + " 但 Skill Registry 中不存在该技能，任务跳过此步骤。")
                    .expectedEvidence(List.of())
                    .resultSummary("未执行")
                    .status("SKIPPED")
                    .build());
            return;
        }
        EngineeringSkill skill = optionalSkill.get();
        EngineeringSkillResultEntity result = skill.execute(task);
        task.setUsedToolCalls(task.getUsedToolCalls() + estimateToolCalls(skill.metadata()));
        mergeSkillOutput(task, skillId, result);
        task.addStep(EngineeringTaskStepEntity.builder()
                .stepNo(stepNo)
                .decision(decision.getDecision())
                .selectedSkill(skillId)
                .reason(decision.getReason())
                .expectedEvidence(result.getEvidence())
                .resultSummary(result.getSummary())
                .rawEvidenceJson(JSON.toJSONString(result.getRawOutput()))
                .status(result.getStatus())
                .build());
    }

    @SuppressWarnings("unchecked")
    private void mergeSkillOutput(EngineeringTaskEntity task, String skillId, EngineeringSkillResultEntity result) {
        Map<String, Object> rawOutput = result == null ? Map.of() : result.getRawOutput();
        Map<String, Object> context = new LinkedHashMap<>();
        if (task.getContext() != null) {
            context.putAll(task.getContext());
        }
        Map<String, Object> skillOutputs = new LinkedHashMap<>();
        Object existingOutputs = context.get("skillOutputs");
        if (existingOutputs instanceof Map<?, ?> existingMap) {
            existingMap.forEach((key, value) -> skillOutputs.put(String.valueOf(key), value));
        }
        skillOutputs.put(skillId, rawOutput == null ? Map.of() : rawOutput);
        context.put("skillOutputs", skillOutputs);
        IncidentFixWorkingMemory workingMemory = getOrCreateWorkingMemory(task, context);
        workingMemory.recordSkillOutput(skillId, rawOutput);
        handleReflectionAfterRepairFailure(task, skillId, result, context, skillOutputs, workingMemory);
        context.put("incidentFixWorkingMemory", workingMemory);
        task.setContext(context);
    }

    private void handleReflectionAfterRepairFailure(EngineeringTaskEntity task,
                                                    String skillId,
                                                    EngineeringSkillResultEntity result,
                                                    Map<String, Object> context,
                                                    Map<String, Object> skillOutputs,
                                                    IncidentFixWorkingMemory workingMemory) {
        if (!"INCIDENT_TO_FIX".equals(task.getTaskType())
                || !isReflectableRepairSkill(skillId)
                || result == null
                || !"FAILED".equals(result.getStatus())) {
            return;
        }
        int reflectionRound = integerValue(context.get("incidentFixReflectionRound"));
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("round", reflectionRound + 1);
        failure.put("failedSkill", skillId);
        failure.put("summary", result.getSummary());
        failure.put("rawOutput", result.getRawOutput() == null ? Map.of() : result.getRawOutput());
        Map<String, Object> diagnostic = buildReflectionDiagnostic(reflectionRound + 1, skillId, result);
        failure.put("diagnostic", diagnostic);

        List<Object> failures = new ArrayList<>();
        Object existingFailures = context.get("incidentFixReflectionFailures");
        if (existingFailures instanceof List<?> values) {
            failures.addAll(values);
        }
        failures.add(failure);
        context.put("incidentFixReflectionFailures", failures);
        context.put("incidentFixLatestFailure", failure);
        context.put("incidentFixReflectionDiagnostics", extractDiagnostics(failures));
        context.put("incidentFixReflectionRound", reflectionRound + 1);

        if (reflectionRound + 1 >= MAX_REFLECTION_ROUNDS) {
            context.put("incidentFixReflectionExhausted", true);
            return;
        }

        skillOutputs.remove("bug_fix");
        skillOutputs.remove("test_verification");
        skillOutputs.remove("release_risk_analysis");
        context.put("skillOutputs", skillOutputs);
        workingMemory.setPatchGeneration(new LinkedHashMap<>());
        workingMemory.setTestVerification(new LinkedHashMap<>());
        workingMemory.setReleaseRisk(new LinkedHashMap<>());
        workingMemory.getFinalReview().put("reflectionRound" + (reflectionRound + 1), failure);
    }

    private List<Object> extractDiagnostics(List<Object> failures) {
        List<Object> diagnostics = new ArrayList<>();
        for (Object failure : failures == null ? List.of() : failures) {
            if (failure instanceof Map<?, ?> map && map.get("diagnostic") instanceof Map<?, ?> diagnostic) {
                Map<String, Object> values = new LinkedHashMap<>();
                diagnostic.forEach((key, value) -> values.put(String.valueOf(key), value));
                diagnostics.add(values);
            }
        }
        return diagnostics;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildReflectionDiagnostic(int round, String skillId, EngineeringSkillResultEntity result) {
        Map<String, Object> rawOutput = result == null || result.getRawOutput() == null ? Map.of() : result.getRawOutput();
        String text = JSON.toJSONString(rawOutput).toLowerCase(Locale.ROOT);
        String failureType = "UNKNOWN";
        List<String> failedFiles = new ArrayList<>();
        List<String> failedMethods = new ArrayList<>();
        List<String> failedCommands = new ArrayList<>();
        List<String> mustFix = new ArrayList<>();
        List<String> mustAvoid = new ArrayList<>();
        List<String> nextAttemptConstraints = new ArrayList<>();

        // --- Priority-ordered failure detection ---

        // Priority 1: PatchScopeGuard
        Object scopeGuard = rawOutput.get("patchScopeGuard");
        if (scopeGuard instanceof Map<?, ?> guardMap && Boolean.FALSE.equals(guardMap.get("passed"))) {
            failureType = "SCOPE_GUARD_FAILED";
            Object gft = guardMap.get("failureType");
            String guardFailureType = gft != null ? String.valueOf(gft) : "METHOD_OUT_OF_SCOPE";
            mustFix.add("Only modify methods listed in repairScope.targetMethods. Guard failure: " + guardFailureType);
            Object violations = guardMap.get("violations");
            if (violations instanceof List<?> vList) {
                vList.stream().map(String::valueOf).forEach(mustAvoid::add);
            }
            Object rs = guardMap.get("repairScope");
            if (rs instanceof Map<?, ?> rsm) {
                mustFix.add("repairScope scopeType=" + rsm.get("scopeType") + ", targetMethods=" + rsm.get("targetMethods"));
            }
            Object changedMethods = guardMap.get("changedMethods");
            if (changedMethods instanceof List<?> cmList) {
                failedMethods.addAll(cmList.stream().map(String::valueOf).toList());
            }
            Object touchedFiles = guardMap.get("touchedFiles");
            if (touchedFiles instanceof List<?> tfList) {
                failedFiles.addAll(tfList.stream().map(String::valueOf).toList());
            }
            nextAttemptConstraints.add("Do NOT modify any method outside repairScope.targetMethods.");
        }

        // Priority 2: PatchApply
        Object patchApply = rawOutput.get("patchApply");
        if (patchApply instanceof Map<?, ?> paMap) {
            if (Boolean.TRUE.equals(paMap.get("requested")) && Boolean.FALSE.equals(paMap.get("applied"))) {
                failureType = isEmptyFailureType(failureType) ? "PATCH_APPLY_FAILED" : failureType;
                mustFix.add("Patch did not apply. Prefer complete fileRewrites with full file content instead of unifiedDiffPatch.");
                mustAvoid.add("Do not use unifiedDiffPatch for reflection rounds — use fileRewrites.");
                nextAttemptConstraints.add("Use fileRewrites ONLY. Leave unifiedDiffPatch empty.");
            }
        }

        // Priority 3: SourceValidation
        Object sourceValidation = rawOutput.get("sourceValidation");
        if (sourceValidation instanceof Map<?, ?> svMap && Boolean.FALSE.equals(svMap.get("valid"))) {
            failureType = isEmptyFailureType(failureType) ? "SOURCE_STRUCTURE_INVALID" : failureType;
            Object errors = svMap.get("errors");
            if (errors instanceof List<?> values) {
                values.stream().map(String::valueOf).forEach(mustFix::add);
            }
            mustAvoid.add("Do not emit unbalanced braces or content after the final Java class brace.");
            failedFiles.addAll(extractFilesFromText(text));
        }

        // Priority 4: CompileGate
        Object compileGate = rawOutput.get("compileGate");
        if (compileGate instanceof Map<?, ?> cgMap) {
            if (Boolean.TRUE.equals(cgMap.get("requested")) && Boolean.FALSE.equals(cgMap.get("success"))) {
                failureType = isEmptyFailureType(failureType) ? "COMPILE_FAILED" : failureType;
                mustFix.add("Production code does not compile. Fix the exact compiler errors before changing logic.");
                Object cgCmd = cgMap.get("command");
                if (cgCmd instanceof String cmd && !cmd.isBlank()) {
                    failedCommands.add(cmd);
                }
                Object cgOut = cgMap.get("output");
                String cgOutput = cgOut != null ? String.valueOf(cgOut) : "";
                failedFiles.addAll(extractFilesFromText(cgOutput));
                nextAttemptConstraints.add("Fix compiler errors first. Do not add new logic until compilation passes.");
            }
        }

        // Priority 5: TestPatchApply
        Object testPatchApply = rawOutput.get("testPatchApply");
        if (testPatchApply instanceof Map<?, ?> tpaMap) {
            if (Boolean.TRUE.equals(tpaMap.get("requested")) && Boolean.FALSE.equals(tpaMap.get("applied"))) {
                failureType = isEmptyFailureType(failureType) ? "TEST_PATCH_APPLY_FAILED" : failureType;
                mustFix.add("Test patch did not apply. Ensure test files use valid package/imports and exist in src/test.");
                mustAvoid.add("Do not generate test patches for non-existent directories.");
            }
        }

        // Priority 6: Test execution failures
        if ("test_verification".equals(skillId)) {
            if (text.contains("testcompile") || text.contains("compilation failure") || text.contains("compilation error")) {
                failureType = isEmptyFailureType(failureType) ? "TEST_COMPILE_FAILED" : failureType;
                mustFix.add("Generated tests do not compile. Match visible production APIs and avoid missing dependencies.");
                failedFiles.addAll(extractFilesFromText(text));
            } else if (text.contains("command timeout") || text.contains("timed out")) {
                failureType = isEmptyFailureType(failureType) ? "TEST_TIMEOUT" : failureType;
                mustFix.add("A Maven verification command timed out. Ensure tests have bounded execution time.");
                mustAvoid.add("Do not use unbounded waits or infinite loops in tests.");
            } else if (text.contains("assertion") || text.contains("expected") && text.contains("actual")
                    || text.contains("failures:") || text.contains("<<< failure!")) {
                failureType = isEmptyFailureType(failureType) ? "TEST_ASSERTION_FAILED" : failureType;
                mustFix.add("Tests executed but assertions failed. Align implementation behavior with the failing assertion.");
                failedFiles.addAll(extractFilesFromText(text));
            }
        }

        // Extract Maven commands from failure text
        failedCommands.addAll(extractMavenCommands(text));

        // Build the unified diagnostic
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("round", round);
        diagnostic.put("failedSkill", skillId);
        diagnostic.put("failureType", failureType);
        diagnostic.put("failedFiles", failedFiles.stream().distinct().toList());
        diagnostic.put("failedMethods", failedMethods.stream().distinct().toList());
        diagnostic.put("failedCommands", failedCommands.stream().distinct().toList());
        diagnostic.put("mustFix", mustFix);
        diagnostic.put("mustAvoid", mustAvoid);
        diagnostic.put("repairScope", extractRepairScope(rawOutput));
        diagnostic.put("rawFailureSummary", abbreviate(result == null ? "" : result.getSummary(), 1500));
        diagnostic.put("nextAttemptConstraints", nextAttemptConstraints);
        return diagnostic;
    }

    private boolean isEmptyFailureType(String ft) {
        return ft == null || ft.isEmpty() || "UNKNOWN".equals(ft);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRepairScope(Map<String, Object> rawOutput) {
        if (rawOutput == null) return Map.of();
        Object guard = rawOutput.get("patchScopeGuard");
        if (guard instanceof Map<?, ?> gm && gm.get("repairScope") instanceof Map<?, ?> rs) {
            Map<String, Object> result = new LinkedHashMap<>();
            rs.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    private List<String> extractMavenCommands(String text) {
        List<String> cmds = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("mvn\\s+[^\"]+").matcher(text == null ? "" : text);
        while (m.find()) {
            String cmd = m.group().trim();
            if (!cmds.contains(cmd)) cmds.add(cmd);
        }
        return cmds;
    }

    private String abbreviate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }

    private List<String> extractFilesFromMessages(List<String> messages) {
        return extractFilesFromText(String.join("\n", messages == null ? List.of() : messages));
    }

    private List<String> extractFilesFromText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([a-zA-Z0-9_./\\\\-]+\\.java)")
                .matcher(text);
        while (matcher.find()) {
            String file = matcher.group(1).replace('\\', '/');
            int srcIndex = file.indexOf("src/");
            files.add(srcIndex >= 0 ? file.substring(srcIndex) : file);
        }
        return files.stream().distinct().toList();
    }

    private boolean isReflectableRepairSkill(String skillId) {
        return "bug_fix".equals(skillId) || "test_verification".equals(skillId);
    }

    private void attachWorkingMemory(EngineeringTaskEntity task) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (task.getContext() != null) {
            context.putAll(task.getContext());
        }
        context.put("incidentFixWorkingMemory", IncidentFixWorkingMemory.initialize(task));
        task.setContext(context);
    }

    private IncidentFixWorkingMemory getOrCreateWorkingMemory(EngineeringTaskEntity task, Map<String, Object> context) {
        Object existing = context.get("incidentFixWorkingMemory");
        if (existing instanceof IncidentFixWorkingMemory workingMemory) {
            return workingMemory;
        }
        return IncidentFixWorkingMemory.initialize(task);
    }

    private IncidentFixWorkingMemory getOrCreateWorkingMemory(EngineeringTaskEntity task) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (task.getContext() != null) {
            context.putAll(task.getContext());
        }
        IncidentFixWorkingMemory workingMemory = getOrCreateWorkingMemory(task, context);
        context.put("incidentFixWorkingMemory", workingMemory);
        task.setContext(context);
        return workingMemory;
    }

    private void addStopStep(EngineeringTaskEntity task, int stepNo, String reason) {
        task.addStep(EngineeringTaskStepEntity.builder()
                .stepNo(stepNo)
                .decision("STOP")
                .selectedSkill(null)
                .reason(reason)
                .expectedEvidence(List.of())
                .resultSummary("任务停止")
                .status("STOPPED")
                .build());
    }

    private boolean isToolBudgetExhausted(EngineeringTaskEntity task) {
        if (task.getMaxToolCalls() == null) {
            return false;
        }
        return task.getUsedToolCalls() != null && task.getUsedToolCalls() >= task.getMaxToolCalls();
    }

    private int estimateToolCalls(EngineeringSkillEntity skill) {
        if (skill.getRequiredTools() == null || skill.getRequiredTools().isEmpty()) {
            return 0;
        }
        return skill.getRequiredTools().size();
    }

    private int resolveMaxRounds(EngineeringTaskEntity request) {
        int requested = request.getMaxRounds() == null ? 6 : request.getMaxRounds();
        if ("INCIDENT_TO_FIX".equals(normalizeTaskType(request.getTaskType()))) {
            return Math.max(requested, 12);
        }
        return requested;
    }

    private int integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String buildFinalSummary(EngineeringTaskEntity task) {
        String outcome = "FAILED".equals(task.getStatus()) ? "执行失败或未收敛" : "执行完成";
        return "CodeOps Incident-to-Fix 任务" + outcome + "：taskType=" + task.getTaskType()
                + "，steps=" + (task.getSteps() == null ? 0 : task.getSteps().size())
                + "，usedToolCalls=" + task.getUsedToolCalls()
                + "。当前已由 Orchestrator 根据 IncidentFixWorkingMemory 逐步选择 Agent，并将线上诊断、代码定位、修复生成、测试验证和发布风险产物写入共享记忆。";
    }

    private boolean isFailureStop(EngineeringTaskEntity task, String stopReason) {
        if (Boolean.TRUE.equals(task.getContext() == null ? null : task.getContext().get("incidentFixReflectionExhausted"))) {
            return true;
        }
        if (stopReason != null && stopReason.contains("工具调用预算")) {
            return true;
        }
        if (stopReason != null && stopReason.contains("最大执行轮数")) {
            return true;
        }
        if (task.getSteps() == null || task.getSteps().isEmpty()) {
            return false;
        }
        return lastStepFailed(task, "bug_fix") || lastStepFailed(task, "test_verification");
    }

    private boolean lastStepFailed(EngineeringTaskEntity task, String skillId) {
        if (task.getSteps() == null || task.getSteps().isEmpty()) {
            return false;
        }
        return task.getSteps().stream()
                .filter(step -> skillId.equals(step.getSelectedSkill()))
                .reduce((first, second) -> second)
                .map(step -> "FAILED".equals(step.getStatus()))
                .orElse(false);
    }

    private String normalizeTaskType(String taskType) {
        if (taskType == null || taskType.trim().isEmpty()) {
            return "CODE_REVIEW";
        }
        return taskType.trim().toUpperCase(Locale.ROOT);
    }

}
