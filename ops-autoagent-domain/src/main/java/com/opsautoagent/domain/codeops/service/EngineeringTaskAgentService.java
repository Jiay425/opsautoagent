package com.opsautoagent.domain.codeops.service;

import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolGateway;
import com.opsautoagent.domain.codeops.agent.skill.EngineeringSkill;
import com.opsautoagent.domain.codeops.agent.skill.EngineeringSkillRegistry;
import com.opsautoagent.domain.codeops.agent.skill.TestVerificationSkill;
import com.opsautoagent.domain.codeops.agent.orchestrator.IncidentFixOrchestratorDecision;
import com.opsautoagent.domain.codeops.agent.orchestrator.IncidentFixOrchestratorPolicy;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskStepEntity;
import com.opsautoagent.domain.codeops.model.entity.IncidentFixWorkingMemory;
import com.opsautoagent.domain.codeops.agent.compaction.ContextCompactionService;
import com.opsautoagent.domain.codeops.agent.memory.IncidentMemoryService;
import com.opsautoagent.domain.codeops.agent.recovery.ErrorRecoveryPolicy;
import com.opsautoagent.domain.codeops.agent.recovery.RecoveryDecision;
import com.opsautoagent.domain.codeops.agent.runtime.AgentExecutionContext;
import com.opsautoagent.domain.codeops.agent.runtime.AgentRuntimeService;
import com.opsautoagent.domain.codeops.agent.runtime.AgentStepTrace;
import com.opsautoagent.domain.codeops.agent.security.HumanApprovalGate;
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

    private final ContextCompactionService compactionService;
    private final ErrorRecoveryPolicy recoveryPolicy;
    private final IncidentMemoryService incidentMemoryService;
    private final HumanApprovalGate humanApprovalGate;
    private final AgentRuntimeService agentRuntimeService;

    private final ConcurrentMap<String, EngineeringTaskEntity> taskStore = new ConcurrentHashMap<>();

    public EngineeringTaskAgentService(EngineeringSkillRegistry skillRegistry,
                                       IncidentFixOrchestratorPolicy orchestratorPolicy,
                                       EngineeringToolGateway toolGateway,
                                       ContextCompactionService compactionService,
                                       ErrorRecoveryPolicy recoveryPolicy,
                                       IncidentMemoryService incidentMemoryService,
                                       HumanApprovalGate humanApprovalGate,
                                       AgentRuntimeService agentRuntimeService) {
        this.skillRegistry = skillRegistry;
        this.orchestratorPolicy = orchestratorPolicy;
        this.toolGateway = toolGateway;
        this.compactionService = compactionService;
        this.recoveryPolicy = recoveryPolicy;
        this.incidentMemoryService = incidentMemoryService;
        this.humanApprovalGate = humanApprovalGate;
        this.agentRuntimeService = agentRuntimeService;
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
        boolean failed = isFailureStop(task, stopReason);
        if (failed) {
            task.setStatus("FAILED");
        } else if (submitApprovalIfNeeded(task)) {
            task.setStatus("WAITING_APPROVAL");
        } else {
            task.setStatus("COMPLETED");
        }
        attachGuardrailSummary(task);
        task.setFinalSummary(buildFinalSummary(task));
        task.setUpdateTime(LocalDateTime.now());
    }

    public HumanApprovalGate.ApprovalRecord approveTask(String taskId) {
        HumanApprovalGate.ApprovalRecord record = humanApprovalGate.approve(taskId);
        EngineeringTaskEntity task = taskStore.get(taskId);
        if (task != null) {
            task.setStatus("COMPLETED");
            task.setFinalSummary(appendLine(task.getFinalSummary(), "人工审批已通过，任务完成。"));
            task.setUpdateTime(LocalDateTime.now());
        }
        return record;
    }

    public HumanApprovalGate.ApprovalRecord rejectTask(String taskId, String reason) {
        HumanApprovalGate.ApprovalRecord record = humanApprovalGate.reject(taskId, reason);
        EngineeringTaskEntity task = taskStore.get(taskId);
        if (task != null) {
            task.setStatus("HUMAN_REJECTED");
            task.setFinalSummary(appendLine(task.getFinalSummary(), "人工审批已拒绝：" + reason));
            task.setUpdateTime(LocalDateTime.now());
        }
        return record;
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
        IncidentFixWorkingMemory workingMemory = getOrCreateWorkingMemory(task);
        AgentExecutionContext runtimeContext = agentRuntimeService.begin(task, stepNo, decision, skill,
                workingMemory, MAX_REFLECTION_ROUNDS, integerValue(task.getContext() == null
                        ? null : task.getContext().get("incidentFixReflectionRound")));
        try {
            EngineeringSkillResultEntity result = skill.execute(task);
            task.setUsedToolCalls(task.getUsedToolCalls() + estimateToolCalls(skill.metadata()));
            AgentStepTrace runtimeTrace = agentRuntimeService.finish(task, runtimeContext, result);
            mergeSkillOutput(task, skillId, result);
            Map<String, Object> rawEvidence = enrichRawOutputWithRuntime(task, result == null ? null : result.getRawOutput(), runtimeTrace);
            task.addStep(EngineeringTaskStepEntity.builder()
                    .stepNo(stepNo)
                    .decision(decision.getDecision())
                    .selectedSkill(skillId)
                    .reason(decision.getReason())
                    .expectedEvidence(result == null ? List.of() : result.getEvidence())
                    .resultSummary(result == null ? "" : result.getSummary())
                    .rawEvidenceJson(JSON.toJSONString(rawEvidence))
                    .status(result == null ? "FAILED" : result.getStatus())
                    .build());
        } catch (Exception e) {
            AgentStepTrace runtimeTrace = agentRuntimeService.fail(task, runtimeContext, e);
            Map<String, Object> rawEvidence = enrichRawOutputWithRuntime(task, Map.of(
                    "errorType", e.getClass().getSimpleName(),
                    "errorMessage", e.getMessage() == null ? "" : e.getMessage()
            ), runtimeTrace);
            task.addStep(EngineeringTaskStepEntity.builder()
                    .stepNo(stepNo)
                    .decision(decision.getDecision())
                    .selectedSkill(skillId)
                    .reason(decision.getReason())
                    .expectedEvidence(List.of())
                    .resultSummary("执行失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                    .rawEvidenceJson(JSON.toJSONString(rawEvidence))
                    .status("FAILED")
                    .build());
            log.warn("CodeOps agent skill failed. taskId={}, skillId={}, stepNo={}",
                    task.getTaskId(), skillId, stepNo, e);
        }
    }

    private Map<String, Object> enrichRawOutputWithRuntime(EngineeringTaskEntity task,
                                                           Map<String, Object> rawOutput,
                                                           AgentStepTrace runtimeTrace) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (rawOutput != null) {
            enriched.putAll(rawOutput);
        }
        if (runtimeTrace == null) {
            return enriched;
        }
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("executionId", runtimeTrace.getExecutionId());
        runtime.put("traceId", runtimeTrace.getTraceId());
        runtime.put("stepNo", runtimeTrace.getStepNo());
        runtime.put("agentOrSkill", runtimeTrace.getAgentOrSkill());
        runtime.put("status", runtimeTrace.getStatus());
        runtime.put("costMillis", runtimeTrace.getCostMillis());
        runtime.put("budget", runtimeTrace.getBudget());
        runtime.put("toolConstraints", runtimeTrace.getToolConstraints());
        runtime.put("outputHighlights", runtimeTrace.getOutputHighlights());
        runtime.put("errorType", runtimeTrace.getErrorType());
        runtime.put("errorMessage", runtimeTrace.getErrorMessage());
        List<Map<String, Object>> toolRuntime = collectToolRuntime(task, runtimeTrace.getExecutionId());
        runtime.put("toolCalls", toolRuntime);
        enriched.put("agentRuntime", runtime);
        enriched.put("toolRuntime", toolRuntime);
        return enriched;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectToolRuntime(EngineeringTaskEntity task, String executionId) {
        if (task == null || task.getContext() == null || executionId == null || executionId.isBlank()) {
            return List.of();
        }
        Object value = task.getContext().get("toolRuntimeTrace");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Object recordExecutionId = rawMap.get("executionId");
            if (!executionId.equals(String.valueOf(recordExecutionId))) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            rawMap.forEach((key, rawValue) -> record.put(String.valueOf(key), rawValue));
            records.add(record);
        }
        return records;
    }

    private boolean submitApprovalIfNeeded(EngineeringTaskEntity task) {
        if (task == null || task.getSteps() == null || task.getSteps().isEmpty()) {
            return false;
        }
        if (!"INCIDENT_TO_FIX".equals(task.getTaskType())) {
            return false;
        }
        Map<String, Object> raw = collectLatestRawOutputs(task);
        boolean patchGenerated = Boolean.TRUE.equals(raw.get("llmGenerated"));
        boolean testsPassed = latestSkillStatus(task, TestVerificationSkill.SKILL_ID, "SUCCESS")
                && hasRealPassingTestResult(raw);
        String riskLevel = extractRiskLevel(raw);
        Map<String, Object> evidenceCoverage = mapValue(raw.get("evidenceCoverage"));
        Map<String, Object> patchQuality = mapValue(raw.get("patchQuality"));
        Map<String, Object> patchSandbox = mapValue(raw.get("patchSandbox"));
        List<String> approvalReasons = approvalReasons(riskLevel, patchGenerated, testsPassed,
                evidenceCoverage, patchQuality, patchSandbox);
        boolean approvalRequired = humanApprovalGate.isApprovalRequired(riskLevel, patchGenerated, testsPassed)
                || (!approvalReasons.isEmpty() && patchGenerated && testsPassed);
        if (!approvalRequired) {
            return false;
        }
        if (humanApprovalGate.getStatus(task.getTaskId()) != null) {
            return true;
        }
        humanApprovalGate.submitForApproval(
                task.getTaskId(),
                value(task.getGoal(), task.getTaskId()),
                String.valueOf(raw.getOrDefault("rootCause", "")),
                abbreviate(String.valueOf(raw.getOrDefault("patchDraft", "")), 1200),
                stringList(raw.get("changedFiles")),
                riskLevel,
                abbreviate(String.valueOf(raw.getOrDefault("testExecutionResults", "")), 1200),
                approvalReasons,
                evidenceCoverage,
                patchQuality,
                patchSandbox
        );
        return true;
    }

    private Map<String, Object> collectLatestRawOutputs(EngineeringTaskEntity task) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (EngineeringTaskStepEntity step : task.getSteps()) {
            String json = step.getRawEvidenceJson();
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                Object parsed = JSON.parse(json);
                if (parsed instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> raw.put(String.valueOf(key), value));
                }
            } catch (Exception ignored) {
            }
        }
        return raw;
    }

    private boolean latestSkillStatus(EngineeringTaskEntity task, String skillId, String expectedStatus) {
        return task.getSteps().stream()
                .filter(step -> skillId.equals(step.getSelectedSkill()))
                .reduce((first, second) -> second)
                .map(step -> expectedStatus.equals(step.getStatus()))
                .orElse(false);
    }

    private boolean hasRealPassingTestResult(Map<String, Object> raw) {
        Object value = raw.get("testExecutionResults");
        String text = "";
        if (value instanceof List<?> list) {
            text = String.join("\n", list.stream().map(String::valueOf).toList());
        } else if (value != null) {
            text = String.valueOf(value);
        }
        if (text.isBlank() || text.contains("真实测试执行未开启")) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("\"success\":false") || lower.contains("\"success\": false")
                || lower.contains("exitcode=1") || lower.contains("exit code: 1")
                || lower.contains("build failure") || lower.contains("<<< failure!")) {
            return false;
        }
        java.util.regex.Matcher failures = java.util.regex.Pattern.compile("failures:\\s*(\\d+)").matcher(lower);
        if (failures.find() && Integer.parseInt(failures.group(1)) > 0) {
            return false;
        }
        java.util.regex.Matcher errors = java.util.regex.Pattern.compile("errors:\\s*(\\d+)").matcher(lower);
        if (errors.find() && Integer.parseInt(errors.group(1)) > 0) {
            return false;
        }
        return lower.contains("build success")
                || lower.contains("\"success\":true")
                || (lower.contains("tests run:") && lower.contains("failures: 0") && lower.contains("errors: 0"));
    }

    private String extractRiskLevel(Map<String, Object> raw) {
        Object report = raw.get("releaseRiskReport");
        if (report instanceof Map<?, ?> map && map.get("riskLevel") != null) {
            return String.valueOf(map.get("riskLevel"));
        }
        Object direct = raw.get("riskLevel");
        return direct == null ? "LOW" : String.valueOf(direct);
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private List<String> approvalReasons(String riskLevel,
                                         boolean patchGenerated,
                                         boolean testsPassed,
                                         Map<String, Object> evidenceCoverage,
                                         Map<String, Object> patchQuality,
                                         Map<String, Object> patchSandbox) {
        if (!patchGenerated || !testsPassed) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        if ("HIGH".equalsIgnoreCase(riskLevel) || "CRITICAL".equalsIgnoreCase(riskLevel)) {
            reasons.add("release risk is " + riskLevel);
        }
        if (Boolean.TRUE.equals(patchQuality.get("requiresHumanApproval"))) {
            reasons.add("patch quality gate requires human approval");
        }
        if (patchQuality.containsKey("minimalChangeScore")
                && integerValue(patchQuality.get("minimalChangeScore")) < 70) {
            reasons.add("minimal change score is below 70");
        }
        if (Boolean.FALSE.equals(patchQuality.get("staticSafetyPassed"))) {
            reasons.add("patch static safety did not pass");
        }
        if (Boolean.FALSE.equals(patchSandbox.get("isolated"))) {
            reasons.add("patch was not verified in an isolated sandbox");
        }
        if (Boolean.TRUE.equals(evidenceCoverage.get("fixtureFallbackUsed"))) {
            reasons.add("diagnosis used fixture fallback evidence");
        }
        double realCoverage = doubleValue(evidenceCoverage.get("realEvidenceCoverage"));
        if (realCoverage > 0D && realCoverage < 0.67D) {
            reasons.add("real telemetry evidence coverage is below 67%");
        }
        return reasons;
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0D;
        }
    }

    private String appendLine(String existing, String line) {
        if (existing == null || existing.isBlank()) {
            return line;
        }
        return existing + "\n" + line;
    }

    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
        int maxRounds = resolveMaxReflectionRounds(context);

        // Adaptive cap: STRICT_SINGLE_METHOD = 1 round, others = 3
        boolean isLastRound = reflectionRound + 1 >= maxRounds;
        if (isLastRound) {
            context.put("incidentFixReflectionExhausted", true);
            context.put("incidentFixReflectionMaxRounds", maxRounds);
        }

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

        // Circuit breaker: detect LLM hallucination loop (same patch generated again)
        String currentPatchHash = extractPatchHash(result);
        String previousPatchHash = context.get("incidentFixLastPatchHash") instanceof String s ? s : "";
        if (currentPatchHash != null && currentPatchHash.equals(previousPatchHash)) {
            log.warn("Hallucination loop detected: same patch hash {} for round {}. Forcing exhaustion.",
                    currentPatchHash, reflectionRound + 1);
            context.put("incidentFixReflectionExhausted", true);
            context.put("incidentFixReflectionExhaustedReason",
                    "Hallucination loop — identical patch generated for 2 consecutive rounds");
            return;
        }
        if (currentPatchHash != null) {
            context.put("incidentFixLastPatchHash", currentPatchHash);
            diagnostic.put("patchHash", currentPatchHash);
        }

        // Store failure pattern for future recall (even on last round)
        storeFailureMemory(task, diagnostic, result);

        if (isLastRound) {
            return; // Don't clean up for another round — we're done
        }

        skillOutputs.remove("bug_fix");
        skillOutputs.remove("test_verification");
        skillOutputs.remove("release_risk_analysis");
        // Compact old outputs before next reflection round
        context.put("skillOutputs", compactionService.compact(skillOutputs, reflectionRound));
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
                List<String> compileErrors = extractCompileErrors(cgOutput);
                mustFix.addAll(compileErrors);
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
        // Extract model tier from rawOutput for flash failure counting
        Object modelRouting = rawOutput.get("modelRouting");
        if (modelRouting instanceof Map<?, ?> mr) {
            diagnostic.put("model", mr.get("model"));
            diagnostic.put("modelTier", mr.get("modelTier"));
        }
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

    /**
     * Extract structured compile errors from Maven output.
     * Pattern: [ERROR] /path/to/File.java:[line] error message
     * Returns list of "File.java:123: error message" strings for reflection diagnostics.
     */
    private List<String> extractCompileErrors(String compileOutput) {
        if (compileOutput == null || compileOutput.isBlank()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        // Match: [ERROR] /path/File.java:[line] message or [ERROR] /path/File.java:[line,column] message
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\[ERROR\\]\\s+(.+?\\.java):\\[?(\\d+)(?:,\\d+)?\\]?\\s+(.+)")
                .matcher(compileOutput);
        while (m.find()) {
            String file = m.group(1).replace('\\', '/');
            int srcIdx = file.indexOf("src/");
            if (srcIdx >= 0) file = file.substring(srcIdx);
            int line = Integer.parseInt(m.group(2));
            String message = m.group(3).trim();
            errors.add(file + ":" + line + ": " + message);
        }
        return errors;
    }

    private int resolveMaxReflectionRounds(Map<String, Object> context) {
        // Extract scopeType from the latest known repairScope in reflection diagnostics
        Object diagnostics = context.get("incidentFixReflectionDiagnostics");
        if (diagnostics instanceof List<?> list) {
            for (Object d : list) {
                if (d instanceof Map<?, ?> dm) {
                    Object rs = dm.get("repairScope");
                    if (rs instanceof Map<?, ?> rsm) {
                        String scopeType = String.valueOf((rsm.get("scopeType") != null ? String.valueOf(rsm.get("scopeType")) : ""));
                        if ("STRICT_SINGLE_METHOD".equals(scopeType)) return 1;
                        if ("MULTI_METHOD".equals(scopeType)) return 3;
                        if ("FULL_FILE".equals(scopeType)) return 3;
                    }
                }
            }
        }
        // Also check skillOutputs for the latest bug_fix's repairScope
        Object skillOutputs = context.get("skillOutputs");
        if (skillOutputs instanceof Map<?, ?> outputs) {
            Object bugFixOutput = outputs.get("bug_fix");
            if (bugFixOutput instanceof Map<?, ?> bfo) {
                Object raw = bfo.get("rawOutput");
                if (raw instanceof Map<?, ?> rawMap) {
                    Object guard = rawMap.get("patchScopeGuard");
                    if (guard instanceof Map<?, ?> gm) {
                        Object rs = gm.get("repairScope");
                        if (rs instanceof Map<?, ?> rsm) {
                            String scopeType = String.valueOf((rsm.get("scopeType") != null ? String.valueOf(rsm.get("scopeType")) : ""));
                            if ("STRICT_SINGLE_METHOD".equals(scopeType)) return 1;
                        }
                    }
                }
            }
        }
        return 3; // default: max 3 rounds
    }

    @SuppressWarnings("unchecked")
    private void storeFailureMemory(EngineeringTaskEntity task, Map<String, Object> diagnostic,
                                     EngineeringSkillResultEntity result) {
        try {
            String failureType = String.valueOf(diagnostic.getOrDefault("failureType", "UNKNOWN"));
            if ("UNKNOWN".equals(failureType)) return;

            String caseName = task.getGoal() != null ? truncate(task.getGoal(), 80) : task.getTaskId();
            String failedMethod = "";
            String violation = "";
            String scopeType = "";

            // Extract scope from result rawOutput
            Map<String, Object> rawOutput = result != null ? result.getRawOutput() : null;
            if (rawOutput != null) {
                Object guard = rawOutput.get("patchScopeGuard");
                if (guard instanceof Map<?, ?> gm) {
                    Object violations = gm.get("violations");
                    if (violations instanceof List<?> vl && !vl.isEmpty()) {
                        violation = vl.get(0).toString();
                    }
                    Object cm = gm.get("changedMethods");
                    if (cm instanceof List<?> cml && !cml.isEmpty()) {
                        failedMethod = String.join(", ", cml.stream().map(String::valueOf).toList());
                    }
                    Object rs = gm.get("repairScope");
                    if (rs instanceof Map<?, ?> rsm) {
                        Object st = rsm.get("scopeType");
                        if (st != null) scopeType = String.valueOf(st);
                    }
                }
            }

            // Build violation description from mustFix/mustAvoid
            if (violation.isEmpty()) {
                Object mustFix = diagnostic.get("mustFix");
                Object mustAvoid = diagnostic.get("mustAvoid");
                if (mustFix instanceof List<?> mfl && !mfl.isEmpty()) {
                    violation = String.join("; ", mfl.stream().map(String::valueOf).limit(2).toList());
                } else if (mustAvoid instanceof List<?> mal && !mal.isEmpty()) {
                    violation = "Must avoid: " + String.join("; ", mal.stream().map(String::valueOf).limit(2).toList());
                }
            }

            incidentMemoryService.storeFailure(
                    task.getTaskId(), caseName, failureType,
                    truncate(failedMethod, 200), truncate(violation, 300), scopeType);
        } catch (Exception ignored) {
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }

    /**
     * Extract a hash of the patch content for detecting hallucination loops.
     * Uses the patchDraft or unifiedDiffPatch from the raw output.
     */
    @SuppressWarnings("unchecked")
    private String extractPatchHash(EngineeringSkillResultEntity result) {
        if (result == null || result.getRawOutput() == null) return null;
        Map<String, Object> raw = result.getRawOutput();
        String patch = "";
        Object draft = raw.get("patchDraft");
        if (draft instanceof String s && !s.isBlank()) patch = s;
        if (patch.isBlank()) {
            Object udp = raw.get("unifiedDiffPatch");
            if (udp instanceof String s && !s.isBlank()) patch = s;
        }
        if (patch.isBlank()) return null;
        // Use a simple hash — enough to detect identical output
        return Integer.toHexString(patch.hashCode());
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

    private void attachGuardrailSummary(EngineeringTaskEntity task) {
        Map<String, Object> summary = buildGuardrailSummary(task);
        Map<String, Object> context = new LinkedHashMap<>();
        if (task.getContext() != null) {
            context.putAll(task.getContext());
        }
        context.put("guardrailSummary", summary);
        IncidentFixWorkingMemory workingMemory = getOrCreateWorkingMemory(task, context);
        workingMemory.setSafetySummary(summary);
        context.put("incidentFixWorkingMemory", workingMemory);
        task.setContext(context);
    }

    private Map<String, Object> buildGuardrailSummary(EngineeringTaskEntity task) {
        Map<String, Object> raw = collectLatestRawOutputs(task);
        Map<String, Object> evidenceCoverage = mapValue(raw.get("evidenceCoverage"));
        Map<String, Object> patchQuality = mapValue(raw.get("patchQuality"));
        Map<String, Object> patchSandbox = mapValue(raw.get("patchSandbox"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("realEvidenceCoverage", evidenceCoverage.getOrDefault("realEvidenceCoverage", 0D));
        summary.put("fixtureFallbackUsed", evidenceCoverage.getOrDefault("fixtureFallbackUsed", false));
        summary.put("patchSandboxMode", patchSandbox.getOrDefault("mode", ""));
        summary.put("patchSandboxIsolated", patchSandbox.getOrDefault("isolated", false));
        summary.put("patchStaticSafetyPassed", patchQuality.getOrDefault("staticSafetyPassed", false));
        summary.put("minimalChangeScore", patchQuality.getOrDefault("minimalChangeScore", 0));
        summary.put("testsPassed", hasRealPassingTestResult(raw));
        summary.put("approvalStatus", humanApprovalGate.getStatus(task.getTaskId()) == null
                ? "NOT_REQUIRED_OR_NOT_SUBMITTED"
                : humanApprovalGate.getStatus(task.getTaskId()).getStatus());
        summary.put("approvalReasons", approvalReasons(extractRiskLevel(raw),
                Boolean.TRUE.equals(raw.get("llmGenerated")),
                hasRealPassingTestResult(raw),
                evidenceCoverage, patchQuality, patchSandbox));
        return summary;
    }

    private String buildFinalSummary(EngineeringTaskEntity task) {
        String outcome = "FAILED".equals(task.getStatus()) ? "执行失败或未收敛" : "执行完成";
        Map<String, Object> guardrail = task.getContext() == null ? Map.of() : mapValue(task.getContext().get("guardrailSummary"));
        return "CodeOps Incident-to-Fix 任务" + outcome + "：taskType=" + task.getTaskType()
                + "，steps=" + (task.getSteps() == null ? 0 : task.getSteps().size())
                + "，usedToolCalls=" + task.getUsedToolCalls()
                + "，realEvidenceCoverage=" + guardrail.getOrDefault("realEvidenceCoverage", "N/A")
                + "，patchSandboxIsolated=" + guardrail.getOrDefault("patchSandboxIsolated", "N/A")
                + "，patchStaticSafetyPassed=" + guardrail.getOrDefault("patchStaticSafetyPassed", "N/A")
                + "，minimalChangeScore=" + guardrail.getOrDefault("minimalChangeScore", "N/A")
                + "，approvalStatus=" + guardrail.getOrDefault("approvalStatus", "N/A")
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
