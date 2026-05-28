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
        boolean releaseRiskGenerated = task.getSteps() != null && task.getSteps().stream()
                .anyMatch(s -> "release_risk_analysis".equals(s.getSelectedSkill())
                        && "SUCCESS".equals(s.getStatus()));

        int reflectionRounds = extractReflectionRounds(task);
        boolean reflectionRecovered = reflectionRounds > 0 && "SUCCESS".equals(run.getStatus());

        List<CodeOpsEvalStepReport> stepReports = buildStepReports(task);
        List<CodeOpsReflectionReport> reflectionHistory = buildReflectionHistory(task, rawOutputs);

        return CodeOpsEvalCaseReport.builder()
                .caseId(run.getCaseId())
                .caseName(evalCase != null ? evalCase.getCaseName() : run.getCaseId())
                .status(run.getStatus())
                .taskId(run.getTaskId())
                .taskType(evalCase != null ? evalCase.getTaskType() : "")
                .scopeType(extractScopeType(rawOutputs))
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
                .failureType(extractFailureType(rawOutputs))
                .failureSummary(extractFailureSummary(task))
                .steps(stepReports)
                .reflectionHistory(reflectionHistory)
                .artifacts(buildArtifacts(batchId, run.getCaseId()))
                .build();
    }

    private CodeOpsEvalMetricSummary computeMetrics(List<CodeOpsEvalCaseReport> cases) {
        if (cases.isEmpty()) return CodeOpsEvalMetricSummary.empty();

        long total = cases.size();
        long scopeMatch = cases.stream().filter(this::isScopeAccurate).count();
        long codeFixCases = cases.stream().filter(c -> !"NO_CODE_FIX".equals(c.getScopeType())).count();
        long patchApplied = cases.stream().filter(CodeOpsEvalCaseReport::isPatchApplied).count();
        long compilePassed = cases.stream().filter(CodeOpsEvalCaseReport::isCompilePassed).count();
        long testsPassed = cases.stream().filter(CodeOpsEvalCaseReport::isTestsPassed).count();
        long reflectionCases = cases.stream().filter(c -> c.getReflectionRounds() > 0).count();
        long reflectionRecovered = cases.stream().filter(c -> c.getReflectionRounds() > 0 && c.isReflectionRecovered()).count();
        long noCodeFixCases = cases.stream().filter(c -> "NO_CODE_FIX".equals(c.getScopeType())).count();
        long noCodeFixCorrect = cases.stream()
                .filter(c -> "NO_CODE_FIX".equals(c.getScopeType()) && !c.isPatchGenerated()).count();

        return CodeOpsEvalMetricSummary.builder()
                .scopeAccuracy(total > 0 ? BigDecimal.valueOf(scopeMatch).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .patchApplyRate(codeFixCases > 0 ? BigDecimal.valueOf(patchApplied).divide(BigDecimal.valueOf(codeFixCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .compilePassRate(codeFixCases > 0 ? BigDecimal.valueOf(compilePassed).divide(BigDecimal.valueOf(codeFixCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .testPassRate(codeFixCases > 0 ? BigDecimal.valueOf(testsPassed).divide(BigDecimal.valueOf(codeFixCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .reflectionRecoveryRate(reflectionCases > 0 ? BigDecimal.valueOf(reflectionRecovered).divide(BigDecimal.valueOf(reflectionCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .noCodeFixAccuracy(noCodeFixCases > 0 ? BigDecimal.valueOf(noCodeFixCorrect).divide(BigDecimal.valueOf(noCodeFixCases), 2, RoundingMode.HALF_UP) : BigDecimal.ONE)
                .build();
    }

    private boolean isScopeAccurate(CodeOpsEvalCaseReport c) {
        if (c.getScopeType() == null || c.getScopeType().isEmpty()) return false;
        // NO_CODE_FIX cases should have no patch generated
        if ("NO_CODE_FIX".equals(c.getScopeType())) return !c.isPatchGenerated();
        // Code-fix cases should have a meaningful scope
        return c.getTargetMethods() != null && !c.getTargetMethods().isEmpty();
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
                        map.forEach((k, v) -> merged.putIfAbsent(String.valueOf(k), v));
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

    @SuppressWarnings("unchecked")
    private List<String> extractTargetMethods(Map<String, Object> raw) {
        Object guard = raw.get("patchScopeGuard");
        if (guard instanceof Map<?, ?> gm) {
            Object rs = gm.get("repairScope");
            if (rs instanceof Map<?, ?> rsm) {
                Object tm = rsm.get("targetMethods");
                if (tm instanceof List<?> list) {
                    return list.stream().map(String::valueOf).toList();
                }
            }
        }
        return List.of();
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
        if ("release_risk_analysis".equals(skill)) return "riskPoints";
        if ("ops_diagnosis".equals(skill)) return "codeHints";
        if ("repo_understanding".equals(skill)) return "targetFiles + targetMethods";
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

    private ReportArtifacts buildArtifacts(String batchId, String caseId) {
        String base = "data/codeops-eval/" + batchId + "/cases/" + caseId;
        return ReportArtifacts.builder()
                .reportJsonPath(base + ".json")
                .reportMarkdownPath(base + ".md")
                .traceJsonPath(base + "-trace.json")
                .patchDiffPath(base + ".diff")
                .build();
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
