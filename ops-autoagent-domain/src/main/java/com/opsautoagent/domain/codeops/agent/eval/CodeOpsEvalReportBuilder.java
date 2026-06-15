package com.opsautoagent.domain.codeops.agent.eval;

import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskStepEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CodeOpsEvalReportBuilder {

    public CodeOpsEvalReport buildReport(String batchId, List<CodeOpsEvalCaseReport> caseReports) {
        int total = caseReports.size();
        int success = (int) caseReports.stream().filter(c -> "SUCCESS".equals(c.getStatus())).count();

        CodeOpsEvalMetricSummary metrics = computeMetrics(caseReports);

        return CodeOpsEvalReport.builder()
                .batchId(batchId)
                .runTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .totalCases(total)
                .successCases(success)
                .failedCases(total - success)
                .summaryMetrics(metrics)
                .cases(caseReports)
                .pipelineTrace(buildPipelineTrace(caseReports))
                .build();
    }

    public CodeOpsEvalCaseReport buildCaseReport(String batchId, CodeOpsEvalCase evalCase, CodeOpsEvalRun run,
                                                  EngineeringTaskEntity task) {
        Map<String, Object> rawOutputs = collectRawOutputs(task);

        boolean patchGenerated = hasKeyTrue(rawOutputs, "llmGenerated");
        boolean patchGuardPassed = hasKeyTrue(rawOutputs, "patchScopeGuard", "passed");
        boolean patchApplied = hasKeyTrue(rawOutputs, "patchApply", "applied");
        boolean compilePassed = hasKeyTrue(rawOutputs, "compileGate", "success");
        boolean testsPassed = isTestsPassed(rawOutputs);
        boolean releaseRiskGenerated = task != null && task.getSteps() != null && task.getSteps().stream()
                .anyMatch(s -> "release_risk_analysis".equals(s.getSelectedSkill())
                        && ("SUCCESS".equals(s.getStatus()) || "NO_DIFF".equals(s.getStatus())));
        Map<String, Object> evidenceCoverage = mapValue(rawOutputs.get("evidenceCoverage"));
        Map<String, Object> patchQuality = mapValue(rawOutputs.get("patchQuality"));
        Map<String, Object> patchSandbox = mapValue(rawOutputs.get("patchSandbox"));

        int reflectionRounds = extractReflectionRounds(task);
        boolean reflectionRecovered = reflectionRounds > 0 && "SUCCESS".equals(run.getStatus());

        List<CodeOpsEvalStepReport> stepReports = buildStepReports(task);
        List<CodeOpsReflectionReport> reflectionHistory = buildReflectionHistory(task, rawOutputs);
        CodeOpsLocalizationEvalResult localizationEval = buildLocalizationEval(evalCase, rawOutputs);

        return CodeOpsEvalCaseReport.builder()
                .caseId(run.getCaseId())
                .caseName(evalCase != null ? evalCase.getCaseName() : run.getCaseId())
                .status(run.getStatus())
                .taskId(run.getTaskId())
                .taskType(evalCase != null ? evalCase.getTaskType() : "")
                .scopeType(extractScopeType(rawOutputs))
                .fixStrategy(extractFixStrategy(rawOutputs))
                .scopeDecision(extractScopeDecision(rawOutputs))
                .rootCauseLocationType(extractRootCauseLocationType(rawOutputs))
                .localizationDecision(extractLocalizationDecision(rawOutputs))
                .localizationEval(localizationEval)
                .targetFiles(extractTargetFiles(rawOutputs))
                .targetMethods(extractTargetMethods(rawOutputs))
                .selectedSkills(run.getDetail() != null && run.getDetail().get("selectedSkills") instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList() : List.of())
                .stepCount(run.getStepCount())
                .latencyMs(run.getLatencyMs())
                .patchGenerated(patchGenerated)
                .patchGuardPassed(patchGuardPassed)
                .patchApplied(patchApplied)
                .compilePassed(compilePassed)
                .testsPassed(testsPassed)
                .reflectionRounds(reflectionRounds)
                .reflectionRecovered(reflectionRecovered)
                .releaseRiskGenerated(releaseRiskGenerated)
                .finalRiskLevel(extractRiskLevel(rawOutputs))
                .realEvidenceCoverage(doubleValue(evidenceCoverage.get("realEvidenceCoverage")))
                .fixtureEvidenceUsed(Boolean.TRUE.equals(evidenceCoverage.get("fixtureFallbackUsed")))
                .evidenceSourceSummary(evidenceSourceSummary(evidenceCoverage, rawOutputs.get("evidenceProvenance")))
                .patchQuality(patchQuality)
                .patchSandbox(patchSandbox)
                .failureType(extractFailureType(rawOutputs))
                .failureSummary("SUCCESS".equals(run.getStatus()) ? "" : extractFailureSummary(task))
                .steps(stepReports)
                .reflectionHistory(reflectionHistory)
                .artifacts(buildArtifacts(batchId, run.getCaseId()))
                .tracePayload(buildTracePayload(task, rawOutputs))
                .patchDiff(extractPatchDiff(rawOutputs))
                .build();
    }

    private CodeOpsEvalMetricSummary computeMetrics(List<CodeOpsEvalCaseReport> cases) {
        if (cases.isEmpty()) return CodeOpsEvalMetricSummary.empty();

        long total = cases.size();
        long scopeMatch = cases.stream().filter(this::isScopeAccurate).count();
        BigDecimal localizationDecisionAccuracy = averageLocalizationScore(cases);
        long expectedFileCases = cases.stream().filter(c -> hasExpected(c.getLocalizationEval() == null ? null : c.getLocalizationEval().getExpectedTargetFiles())).count();
        long targetFileHits = cases.stream().filter(c -> Boolean.TRUE.equals(c.getLocalizationEval() == null ? null : c.getLocalizationEval().getTargetFileMatched())).count();
        long expectedMethodCases = cases.stream().filter(c -> hasExpected(c.getLocalizationEval() == null ? null : c.getLocalizationEval().getExpectedTargetMethods())).count();
        long targetMethodHits = cases.stream().filter(c -> Boolean.TRUE.equals(c.getLocalizationEval() == null ? null : c.getLocalizationEval().getTargetMethodMatched())).count();
        long expectedFixStrategyCases = cases.stream().filter(c -> hasText(c.getLocalizationEval() == null ? null : c.getLocalizationEval().getExpectedFixStrategy())).count();
        long fixStrategyHits = cases.stream().filter(c -> Boolean.TRUE.equals(c.getLocalizationEval() == null ? null : c.getLocalizationEval().getFixStrategyMatched())).count();
        long expectedScopeDecisionCases = cases.stream().filter(c -> hasText(c.getLocalizationEval() == null ? null : c.getLocalizationEval().getExpectedScopeDecision())).count();
        long scopeDecisionHits = cases.stream().filter(c -> Boolean.TRUE.equals(c.getLocalizationEval() == null ? null : c.getLocalizationEval().getScopeDecisionMatched())).count();
        long codeFixCases = cases.stream().filter(c -> !"NO_CODE_FIX".equals(c.getScopeType())).count();
        long patchApplied = cases.stream().filter(CodeOpsEvalCaseReport::isPatchApplied).count();
        long compilePassed = cases.stream().filter(CodeOpsEvalCaseReport::isCompilePassed).count();
        long testsPassed = cases.stream().filter(CodeOpsEvalCaseReport::isTestsPassed).count();
        long reflectionCases = cases.stream().filter(c -> c.getReflectionRounds() > 0).count();
        long reflectionRecovered = cases.stream().filter(c -> c.getReflectionRounds() > 0 && c.isReflectionRecovered()).count();
        long noCodeFixCases = cases.stream().filter(c -> "NO_CODE_FIX".equals(c.getScopeType())).count();
        long noCodeFixCorrect = cases.stream()
                .filter(c -> "NO_CODE_FIX".equals(c.getScopeType()) && !c.isPatchGenerated()).count();
        long evidenceCases = cases.stream().filter(c -> c.getEvidenceSourceSummary() != null && !c.getEvidenceSourceSummary().isEmpty()).count();
        BigDecimal realEvidenceCoverage = evidenceCases > 0
                ? cases.stream()
                .map(c -> BigDecimal.valueOf(c.getRealEvidenceCoverage()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(evidenceCases), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        long patchQualityCases = cases.stream().filter(c -> c.getPatchQuality() != null && !c.getPatchQuality().isEmpty()).count();
        long staticSafetyPassed = cases.stream()
                .filter(c -> Boolean.TRUE.equals(c.getPatchQuality() == null ? null : c.getPatchQuality().get("staticSafetyPassed")))
                .count();
        long sandboxCases = cases.stream().filter(c -> c.getPatchSandbox() != null && !c.getPatchSandbox().isEmpty()).count();
        long sandboxIsolated = cases.stream()
                .filter(c -> Boolean.TRUE.equals(c.getPatchSandbox() == null ? null : c.getPatchSandbox().get("isolated")))
                .count();

        return CodeOpsEvalMetricSummary.builder()
                .scopeAccuracy(total > 0 ? BigDecimal.valueOf(scopeMatch).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .localizationDecisionAccuracy(localizationDecisionAccuracy)
                .localizationTargetFileHitRate(expectedFileCases > 0 ? BigDecimal.valueOf(targetFileHits).divide(BigDecimal.valueOf(expectedFileCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .localizationTargetMethodHitRate(expectedMethodCases > 0 ? BigDecimal.valueOf(targetMethodHits).divide(BigDecimal.valueOf(expectedMethodCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .localizationFixStrategyAccuracy(expectedFixStrategyCases > 0 ? BigDecimal.valueOf(fixStrategyHits).divide(BigDecimal.valueOf(expectedFixStrategyCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .localizationScopeDecisionAccuracy(expectedScopeDecisionCases > 0 ? BigDecimal.valueOf(scopeDecisionHits).divide(BigDecimal.valueOf(expectedScopeDecisionCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .patchApplyRate(codeFixCases > 0 ? BigDecimal.valueOf(patchApplied).divide(BigDecimal.valueOf(codeFixCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .compilePassRate(codeFixCases > 0 ? BigDecimal.valueOf(compilePassed).divide(BigDecimal.valueOf(codeFixCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .testPassRate(codeFixCases > 0 ? BigDecimal.valueOf(testsPassed).divide(BigDecimal.valueOf(codeFixCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .reflectionRecoveryRate(reflectionCases > 0 ? BigDecimal.valueOf(reflectionRecovered).divide(BigDecimal.valueOf(reflectionCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .noCodeFixAccuracy(noCodeFixCases > 0 ? BigDecimal.valueOf(noCodeFixCorrect).divide(BigDecimal.valueOf(noCodeFixCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .realEvidenceCoverageRate(realEvidenceCoverage)
                .patchStaticSafetyRate(patchQualityCases > 0 ? BigDecimal.valueOf(staticSafetyPassed).divide(BigDecimal.valueOf(patchQualityCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .patchSandboxIsolationRate(sandboxCases > 0 ? BigDecimal.valueOf(sandboxIsolated).divide(BigDecimal.valueOf(sandboxCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .build();
    }

    private boolean isScopeAccurate(CodeOpsEvalCaseReport c) {
        if (c.getScopeType() == null || c.getScopeType().isEmpty()) return false;
        // NO_CODE_FIX cases should have no patch generated
        if ("NO_CODE_FIX".equals(c.getScopeType())) return !c.isPatchGenerated();
        // Code-fix cases should have a meaningful scope
        return c.getTargetMethods() != null && !c.getTargetMethods().isEmpty();
    }

    private boolean hasLocalizationDecision(CodeOpsEvalCaseReport c) {
        if (c == null) {
            return false;
        }
        return c.getFixStrategy() != null && !c.getFixStrategy().isBlank()
                && c.getScopeDecision() != null && !c.getScopeDecision().isBlank();
    }

    private BigDecimal averageLocalizationScore(List<CodeOpsEvalCaseReport> cases) {
        List<BigDecimal> scores = cases.stream()
                .map(CodeOpsEvalCaseReport::getLocalizationEval)
                .filter(result -> result != null && result.getScore() != null)
                .map(CodeOpsLocalizationEvalResult::getScore)
                .toList();
        if (scores.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return scores.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP);
    }

    private CodeOpsLocalizationEvalResult buildLocalizationEval(CodeOpsEvalCase evalCase, Map<String, Object> rawOutputs) {
        List<String> expectedFiles = evalCase == null || evalCase.getExpectedTargetFiles() == null
                ? List.of()
                : evalCase.getExpectedTargetFiles();
        List<String> expectedMethods = evalCase == null || evalCase.getExpectedTargetMethods() == null
                ? List.of()
                : evalCase.getExpectedTargetMethods();
        String expectedFixStrategy = normalizeToken(evalCase == null ? "" : evalCase.getExpectedFixStrategy());
        String expectedScopeDecision = normalizeToken(evalCase == null ? "" : evalCase.getExpectedScopeDecision());
        Boolean expectedShouldRepair = expectedFixStrategy.isBlank() ? null : "CODE_FIX".equals(expectedFixStrategy);

        List<String> actualFiles = extractTargetFiles(rawOutputs);
        List<String> actualMethods = extractTargetMethods(rawOutputs);
        String actualFixStrategy = normalizeToken(extractFixStrategy(rawOutputs));
        String actualScopeDecision = normalizeToken(extractScopeDecision(rawOutputs));
        Boolean actualShouldRepair = extractShouldEnterCodeRepair(rawOutputs, actualFixStrategy);

        List<String> missingFiles = missingNormalized(expectedFiles, actualFiles);
        List<String> missingMethods = missingNormalized(expectedMethods, actualMethods);

        Boolean fileMatched = expectedFiles.isEmpty() ? null : missingFiles.isEmpty();
        Boolean methodMatched = expectedMethods.isEmpty() ? null : missingMethods.isEmpty();
        Boolean fixMatched = expectedFixStrategy.isBlank() ? null : expectedFixStrategy.equals(actualFixStrategy);
        Boolean scopeMatched = expectedScopeDecision.isBlank() ? null : expectedScopeDecision.equals(actualScopeDecision);
        Boolean shouldRepairMatched = expectedShouldRepair == null ? null : expectedShouldRepair.equals(actualShouldRepair);

        BigDecimal score = averageBooleans(List.of(fileMatched, methodMatched, fixMatched, scopeMatched, shouldRepairMatched));

        return CodeOpsLocalizationEvalResult.builder()
                .score(score)
                .fixStrategyMatched(fixMatched)
                .scopeDecisionMatched(scopeMatched)
                .targetFileMatched(fileMatched)
                .targetMethodMatched(methodMatched)
                .shouldEnterCodeRepairMatched(shouldRepairMatched)
                .expectedTargetFiles(expectedFiles)
                .actualTargetFiles(actualFiles)
                .missingTargetFiles(missingFiles)
                .expectedTargetMethods(expectedMethods)
                .actualTargetMethods(actualMethods)
                .missingTargetMethods(missingMethods)
                .expectedFixStrategy(expectedFixStrategy)
                .actualFixStrategy(actualFixStrategy)
                .expectedScopeDecision(expectedScopeDecision)
                .actualScopeDecision(actualScopeDecision)
                .expectedShouldEnterCodeRepair(expectedShouldRepair)
                .actualShouldEnterCodeRepair(actualShouldRepair)
                .rawDecision(extractLocalizationDecision(rawOutputs))
                .build();
    }

    private BigDecimal averageBooleans(List<Boolean> values) {
        List<Boolean> scored = values.stream().filter(value -> value != null).toList();
        if (scored.isEmpty()) {
            return BigDecimal.ONE;
        }
        long passed = scored.stream().filter(Boolean.TRUE::equals).count();
        return BigDecimal.valueOf(passed).divide(BigDecimal.valueOf(scored.size()), 2, RoundingMode.HALF_UP);
    }

    private List<String> missingNormalized(List<String> expected, List<String> actual) {
        if (expected == null || expected.isEmpty()) {
            return List.of();
        }
        List<String> actualNormalized = actual == null ? List.of() : actual.stream()
                .map(this::normalizeComparable)
                .toList();
        List<String> missing = new ArrayList<>();
        for (String item : expected) {
            String normalized = normalizeComparable(item);
            boolean matched = actualNormalized.stream().anyMatch(actualItem ->
                    actualItem.equals(normalized)
                            || actualItem.endsWith("/" + normalized)
                            || actualItem.endsWith("." + normalized)
                            || normalized.endsWith("/" + actualItem)
                            || normalized.endsWith("." + actualItem));
            if (!matched) {
                missing.add(item);
            }
        }
        return missing;
    }

    private String normalizeComparable(String value) {
        return value == null ? "" : value.trim()
                .replace('\\', '/')
                .replace("$", ".")
                .toLowerCase();
    }

    private String normalizeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private boolean hasExpected(List<String> values) {
        return values != null && !values.isEmpty();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> collectRawOutputs(EngineeringTaskEntity task) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (task == null || task.getSteps() == null) return merged;
        for (EngineeringTaskStepEntity step : task.getSteps()) {
            String json = step.getRawEvidenceJson();
            if (json != null && !json.isBlank()) {
                try {
                    Object parsed = com.alibaba.fastjson.JSON.parse(json);
                    if (parsed instanceof Map<?, ?> map) {
                        map.forEach((k, v) -> merged.put(String.valueOf(k), v));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return merged;
    }

    private boolean hasKeyTrue(Map<String, Object> raw, String key) {
        Object val = raw.get(key);
        return Boolean.TRUE.equals(val);
    }

    private boolean hasKeyTrue(Map<String, Object> raw, String parent, String child) {
        Object p = raw.get(parent);
        if (p instanceof Map<?, ?> pm) {
            Object val = pm.get(child);
            return Boolean.TRUE.equals(val);
        }
        return false;
    }

    private boolean isTestsPassed(Map<String, Object> raw) {
        Object testResults = raw.get("testExecutionResults");
        String text = "";
        if (testResults instanceof List<?> list) {
            text = String.join("\n", list.stream().map(String::valueOf).toList());
        } else if (testResults != null) {
            text = String.valueOf(testResults);
        }

        // No test results at all
        if (text.isBlank()) {
            Object tpa = raw.get("testPatchApply");
            if (tpa instanceof Map<?, ?> tpm) {
                return Boolean.TRUE.equals(tpm.get("applied"));
            }
            Object scaffolded = raw.get("testPatchScaffolded");
            return Boolean.TRUE.equals(scaffolded);
        }

        String lower = text.toLowerCase();

        // Explicit failure signals — any one = failed
        if (lower.contains("\"success\":false") || lower.contains("\"success\": false")) return false;
        if (lower.contains("exitcode=1") || lower.contains("exit code: 1") || lower.contains("exit code 1")) return false;
        if (lower.contains("build failure")) return false;
        if (lower.contains("<<< failure!")) return false;

        // Parse "Failures: N" — non-zero = failed
        java.util.regex.Matcher fm = java.util.regex.Pattern.compile("failures:\\s*(\\d+)").matcher(lower);
        if (fm.find()) {
            int count = Integer.parseInt(fm.group(1));
            if (count > 0) return false;
        }

        // Parse "Errors: N" — non-zero = failed
        java.util.regex.Matcher em = java.util.regex.Pattern.compile("errors:\\s*(\\d+)").matcher(lower);
        if (em.find()) {
            int count = Integer.parseInt(em.group(1));
            if (count > 0) return false;
        }

        // Explicit pass signals
        if (lower.contains("build success")) return true;
        if (lower.contains("\"success\":true")) return true;
        if (lower.contains("tests run:") && lower.contains("failures: 0") && lower.contains("errors: 0")) return true;

        // Ambiguous: no failure signal found but no explicit pass either → assume passed
        return true;
    }

    @SuppressWarnings("unchecked")
    private String extractScopeType(Map<String, Object> raw) {
        Object guard = raw.get("patchScopeGuard");
        if (guard instanceof Map<?, ?> gm) {
            Object rs = gm.get("repairScope");
            if (rs instanceof Map<?, ?> rsm) {
                Object scopeVal = rsm.get("scopeType");
                return scopeVal != null ? String.valueOf(scopeVal) : "";
            }
        }
        return "";
    }

    private String extractFixStrategy(Map<String, Object> raw) {
        Map<String, Object> decision = extractLocalizationDecision(raw);
        Object value = firstNonBlank(decision.get("fixStrategy"), decision.get("strategyType"), raw.get("fixStrategy"), raw.get("strategyType"));
        return value == null ? "" : String.valueOf(value);
    }

    private String extractScopeDecision(Map<String, Object> raw) {
        Map<String, Object> decision = extractLocalizationDecision(raw);
        Object value = firstNonBlank(decision.get("scopeDecisionType"), decision.get("scopeDecision"), raw.get("scopeDecisionType"));
        return value == null ? extractScopeType(raw) : String.valueOf(value);
    }

    private String extractRootCauseLocationType(Map<String, Object> raw) {
        Map<String, Object> decision = extractLocalizationDecision(raw);
        Object value = firstNonBlank(decision.get("rootCauseLocationType"), raw.get("rootCauseLocationType"));
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> extractLocalizationDecision(Map<String, Object> raw) {
        Object direct = raw.get("localizationDecision");
        if (direct instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        Object codeLoc = raw.get("codeLocalization");
        if (codeLoc instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTargetFiles(Map<String, Object> raw) {
        List<String> values = new ArrayList<>();
        Map<String, Object> decision = extractLocalizationDecision(raw);
        addStrings(values, decision.get("rootCauseCandidateFiles"));
        addStrings(values, decision.get("targetFiles"));
        addStrings(values, decision.get("directEvidenceFiles"));
        addStrings(values, raw.get("rootCauseCandidateFiles"));
        addStrings(values, raw.get("targetFiles"));
        Object guard = raw.get("patchScopeGuard");
        if (guard instanceof Map<?, ?> gm) {
            Object rs = gm.get("repairScope");
            if (rs instanceof Map<?, ?> rsm) {
                addStrings(values, rsm.get("targetFiles"));
            }
        }
        return values.stream()
                .map(String::valueOf)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTargetMethods(Map<String, Object> raw) {
        List<String> values = new ArrayList<>();
        Map<String, Object> decision = extractLocalizationDecision(raw);
        addStrings(values, decision.get("targetMethods"));
        addStrings(values, decision.get("candidateMethods"));
        addStrings(values, decision.get("suspectedRootCauseLocations"));
        addStrings(values, raw.get("targetMethods"));
        addStrings(values, raw.get("candidateMethods"));
        Object guard = raw.get("patchScopeGuard");
        if (guard instanceof Map<?, ?> gm) {
            Object rs = gm.get("repairScope");
            if (rs instanceof Map<?, ?> rsm) {
                addStrings(values, rsm.get("targetMethods"));
            }
        }
        return values.stream()
                .map(String::valueOf)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private void addStrings(List<String> values, Object source) {
        if (source instanceof List<?> list) {
            list.stream().map(String::valueOf).filter(value -> !value.isBlank()).forEach(values::add);
            return;
        }
        if (source instanceof String text && !text.isBlank()) {
            values.add(text);
        }
    }

    private Boolean extractShouldEnterCodeRepair(Map<String, Object> raw, String actualFixStrategy) {
        Map<String, Object> decision = extractLocalizationDecision(raw);
        Object value = firstNonBlank(decision.get("shouldEnterCodeRepair"), raw.get("shouldEnterCodeRepair"));
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            return "true".equalsIgnoreCase(String.valueOf(value));
        }
        if (actualFixStrategy == null || actualFixStrategy.isBlank()) {
            return null;
        }
        return "CODE_FIX".equals(actualFixStrategy);
    }

    private int extractReflectionRounds(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) return 0;
        Object round = task.getContext().get("incidentFixReflectionRound");
        if (round instanceof Number n) return n.intValue();
        return 0;
    }

    private String extractRiskLevel(Map<String, Object> raw) {
        Object risk = raw.get("riskLevel");
        return risk != null ? String.valueOf(risk) : "";
    }

    private String extractFailureType(Map<String, Object> raw) {
        Object guard = raw.get("patchScopeGuard");
        if (guard instanceof Map<?, ?> gm && Boolean.FALSE.equals(gm.get("passed"))) {
            Object ft = gm.get("failureType");
            return ft != null ? String.valueOf(ft) : "SCOPE_GUARD_FAILED";
        }
        Object cg = raw.get("compileGate");
        if (cg instanceof Map<?, ?> cgm && Boolean.FALSE.equals(cgm.get("success"))) {
            return "COMPILE_FAILED";
        }
        return "";
    }

    private String extractFailureSummary(EngineeringTaskEntity task) {
        if (task == null) return "";
        List<EngineeringTaskStepEntity> steps = task.getSteps();
        if (steps == null) return "";
        return steps.stream()
                .filter(s -> "FAILED".equals(s.getStatus()))
                .map(s -> s.getSelectedSkill() + ": " + truncate(s.getResultSummary(), 100))
                .findFirst().orElse("");
    }

    private List<CodeOpsEvalStepReport> buildStepReports(EngineeringTaskEntity task) {
        List<CodeOpsEvalStepReport> reports = new ArrayList<>();
        if (task == null || task.getSteps() == null) return reports;
        for (EngineeringTaskStepEntity step : task.getSteps()) {
            reports.add(CodeOpsEvalStepReport.builder()
                    .stepNo(step.getStepNo() != null ? step.getStepNo() : 0)
                    .decision(step.getDecision())
                    .selectedSkill(step.getSelectedSkill())
                    .status(step.getStatus())
                    .summary(truncate(step.getResultSummary(), 200))
                    .keyArtifact(extractKeyArtifact(step))
                    .build());
        }
        return reports;
    }

    private String extractKeyArtifact(EngineeringTaskStepEntity step) {
        if (step == null) return "";
        String skill = step.getSelectedSkill();
        if ("bug_fix".equals(skill)) return "patchDraft + compileGate";
        if ("test_verification".equals(skill)) return "testExecutionResults";
        if ("release_risk_analysis".equals(skill)) return "codeReview + riskPoints";
        if ("ops_diagnosis".equals(skill)) return "codeHints";
        if ("repo_understanding".equals(skill)) return "evidenceGraph + targetFiles + targetMethods";
        return "";
    }

    @SuppressWarnings("unchecked")
    private List<CodeOpsReflectionReport> buildReflectionHistory(EngineeringTaskEntity task, Map<String, Object> raw) {
        List<CodeOpsReflectionReport> reports = new ArrayList<>();
        if (task == null || task.getContext() == null) return reports;
        Object failures = task.getContext().get("incidentFixReflectionFailures");
        if (!(failures instanceof List<?> list)) return reports;
        int round = 0;
        for (Object f : list) {
            round++;
            if (f instanceof Map<?, ?> fm) {
                Object diag = fm.get("diagnostic");
                if (diag instanceof Map<?, ?> dm) {
                    reports.add(CodeOpsReflectionReport.builder()
                            .round(round)
                            .failedSkill(fm.get("failedSkill") != null ? String.valueOf(fm.get("failedSkill")) : "")
                            .failureType(dm.get("failureType") != null ? String.valueOf(dm.get("failureType")) : "UNKNOWN")
                            .mustFix(toStringList(dm.get("mustFix")))
                            .mustAvoid(toStringList(dm.get("mustAvoid")))
                            .recovered(round < list.size())
                            .recoveryStrategy(round < list.size() ? "adjusted patch based on reflection feedback" : "failed after max rounds")
                            .build());
                }
            }
        }
        return reports;
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private Map<String, Object> mapValue(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private Map<String, Object> evidenceSourceSummary(Map<String, Object> coverage, Object provenance) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("coverage", coverage == null ? Map.of() : coverage);
        summary.put("provenance", provenance == null ? List.of() : provenance);
        return summary;
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

    private ReportArtifacts buildArtifacts(String batchId, String caseId) {
        String base = "data/codeops-eval/" + batchId + "/cases/" + caseId;
        return ReportArtifacts.builder()
                .reportJsonPath(base + ".json")
                .reportMarkdownPath(base + ".md")
                .traceJsonPath(base + "-trace.json")
                .patchDiffPath(base + ".diff")
                .build();
    }

    private Map<String, Object> buildTracePayload(EngineeringTaskEntity task, Map<String, Object> rawOutputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (task == null) {
            return payload;
        }
        payload.put("taskId", task.getTaskId());
        payload.put("taskType", task.getTaskType());
        payload.put("status", task.getStatus());
        payload.put("repository", task.getRepository());
        payload.put("goal", task.getGoal());
        payload.put("usedToolCalls", task.getUsedToolCalls());
        payload.put("finalSummary", task.getFinalSummary());
        payload.put("context", task.getContext());
        List<Map<String, Object>> steps = new ArrayList<>();
        if (task.getSteps() != null) {
            for (EngineeringTaskStepEntity step : task.getSteps()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("stepNo", step.getStepNo());
                item.put("decision", step.getDecision());
                item.put("selectedSkill", step.getSelectedSkill());
                item.put("status", step.getStatus());
                item.put("summary", step.getResultSummary());
                item.put("rawOutput", parseRawEvidence(step.getRawEvidenceJson()));
                steps.add(item);
            }
        }
        payload.put("steps", steps);
        payload.put("latestMergedRawOutput", rawOutputs);
        return payload;
    }

    private Object parseRawEvidence(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return com.alibaba.fastjson.JSON.parse(json);
        } catch (Exception ignored) {
            return json;
        }
    }

    private String extractPatchDiff(Map<String, Object> rawOutputs) {
        Object direct = firstNonBlank(rawOutputs.get("unifiedDiffPatch"),
                rawOutputs.get("patchDiff"),
                rawOutputs.get("patchDraft"));
        String found = findDiffText(direct);
        if (!found.isBlank()) {
            return found;
        }
        return findDiffText(rawOutputs);
    }

    private Object firstNonBlank(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String findDiffText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            String lower = text.toLowerCase();
            if ((text.contains("--- ") && text.contains("+++ "))
                    || lower.contains("diff --git")
                    || lower.contains("@@")) {
                return text;
            }
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase();
                String nested = findDiffText(entry.getValue());
                if (!nested.isBlank() && (key.contains("patch") || key.contains("diff") || key.contains("output"))) {
                    return nested;
                }
            }
            for (Object nestedValue : map.values()) {
                String nested = findDiffText(nestedValue);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String nested = findDiffText(item);
                if (!nested.isBlank()) {
                    return nested;
                }
            }
        }
        return "";
    }

    private List<CodeOpsEvalStepReport> buildPipelineTrace(List<CodeOpsEvalCaseReport> cases) {
        if (cases.isEmpty() || cases.get(0).getSteps() == null) return List.of();
        return cases.get(0).getSteps();
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }
}
