package com.opsautoagent.domain.codeops.agent.eval;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CodeOpsReportMarkdownGenerator {

    public String generateSummaryReport(CodeOpsEvalReport report) {
        StringBuilder md = new StringBuilder();
        md.append("# CodeOps Incident-to-Fix Eval Report\n\n");
        md.append("**Batch:** ").append(report.getBatchId()).append("\n");
        md.append("**Run Time:** ").append(report.getRunTime()).append("\n\n");

        md.append("## Summary\n\n");
        md.append("| Metric | Value |\n|---|---|\n");
        md.append("| Total Cases | ").append(report.getTotalCases()).append(" |\n");
        md.append("| Success Cases | ").append(report.getSuccessCases()).append(" |\n");
        md.append("| Failed Cases | ").append(report.getFailedCases()).append(" |\n");

        CodeOpsEvalMetricSummary m = report.getSummaryMetrics();
        if (m != null) {
            md.append("| Scope Accuracy | ").append(pct(m.getScopeAccuracy())).append(" |\n");
            md.append("| Patch Apply Rate | ").append(pct(m.getPatchApplyRate())).append(" |\n");
            md.append("| Compile Pass Rate | ").append(pct(m.getCompilePassRate())).append(" |\n");
            md.append("| Test Pass Rate | ").append(pct(m.getTestPassRate())).append(" |\n");
            md.append("| Reflection Recovery Rate | ").append(pct(m.getReflectionRecoveryRate())).append(" |\n");
            md.append("| No-Code-Fix Accuracy | ").append(pct(m.getNoCodeFixAccuracy())).append(" |\n");
            md.append("| Real Evidence Coverage | ").append(pct(m.getRealEvidenceCoverageRate())).append(" |\n");
            md.append("| Patch Static Safety Rate | ").append(pct(m.getPatchStaticSafetyRate())).append(" |\n");
            md.append("| Patch Sandbox Isolation Rate | ").append(pct(m.getPatchSandboxIsolationRate())).append(" |\n");
        }

        md.append("\n## Case Results\n\n");
        md.append("| Case | Scope | Status | Steps | Reflection | Patch | Compile | Test | Risk |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (CodeOpsEvalCaseReport c : report.getCases()) {
            md.append("| ").append(c.getCaseName()).append(" | ");
            md.append(c.getScopeType()).append(" | ");
            md.append(c.getStatus()).append(" | ");
            md.append(c.getStepCount()).append(" | ");
            md.append(c.getReflectionRounds() > 0 ? "R" + c.getReflectionRounds() + (c.isReflectionRecovered() ? " OK" : " FAIL") : "-").append(" | ");
            md.append(yn(c.isPatchApplied())).append(" | ");
            md.append(yn(c.isCompilePassed())).append(" | ");
            md.append(yn(c.isTestsPassed())).append(" | ");
            md.append(yn(c.isReleaseRiskGenerated())).append(" |\n");
        }

        md.append("\n## Architecture Trace\n\n");
        md.append("```\n");
        md.append("Alert -> Ops Evidence -> Code Localization -> RepairScope\n");
        md.append("  -> Code Repair Agent -> PatchScopeGuard -> Compile/Test\n");
        md.append("  -> Reflection (max 3 rounds) -> Release Risk\n");
        md.append("```\n");

        md.append("\n## Repair Scope Distribution\n\n");
        for (CodeOpsEvalCaseReport c : report.getCases()) {
            if (c.getTargetMethods() != null && !c.getTargetMethods().isEmpty()) {
                md.append("- **").append(c.getCaseName()).append("**: `")
                        .append(c.getScopeType()).append("` → ")
                        .append(String.join(", ", c.getTargetMethods())).append("\n");
            } else {
                md.append("- **").append(c.getCaseName()).append("**: `")
                        .append(c.getScopeType()).append("` (no target methods)\n");
            }
        }

        md.append("\n## Key Findings\n\n");
        for (CodeOpsEvalCaseReport c : report.getCases()) {
            md.append("- **").append(c.getCaseName()).append("**: ");
            if ("STRICT_SINGLE_METHOD".equals(c.getScopeType())) {
                md.append("demonstrates STRICT_SINGLE_METHOD repair — only the incident-targeted method was modified.\n");
            } else if ("MULTI_METHOD".equals(c.getScopeType()) || "FULL_FILE".equals(c.getScopeType())) {
                md.append("demonstrates multi-method repair with Guard enforcing scope constraints.\n");
            } else if ("NO_CODE_FIX".equals(c.getScopeType())) {
                md.append("demonstrates NO_CODE_FIX decision — correctly identified as non-code incident.\n");
            }
            if (c.getReflectionRounds() > 0) {
                md.append("  - ").append(c.getReflectionRounds()).append(" reflection round(s), ")
                        .append(c.isReflectionRecovered() ? "successfully recovered." : "exhausted retry limit.").append("\n");
            }
        }

        return md.toString();
    }

    public String generateCaseReport(CodeOpsEvalCaseReport c) {
        StringBuilder md = new StringBuilder();
        md.append("# Case: ").append(c.getCaseName()).append("\n\n");
        md.append("**Status:** ").append(c.getStatus()).append(" | ");
        md.append("**TaskId:** ").append(c.getTaskId()).append(" | ");
        md.append("**Steps:** ").append(c.getStepCount()).append(" | ");
        md.append("**Latency:** ").append(c.getLatencyMs()).append("ms\n\n");

        md.append("## Repair Scope\n\n```json\n");
        md.append("{\n");
        md.append("  \"scopeType\": \"").append(c.getScopeType()).append("\",\n");
        md.append("  \"targetMethods\": ").append(toJsonArray(c.getTargetMethods())).append("\n");
        md.append("}\n```\n\n");

        md.append("## Agent Steps\n\n");
        md.append("| Step | Skill | Status | Summary |\n|---|---|---|---|\n");
        if (c.getSteps() != null) {
            for (CodeOpsEvalStepReport s : c.getSteps()) {
                md.append("| ").append(s.getStepNo()).append(" | ");
                md.append(s.getSelectedSkill() != null ? s.getSelectedSkill() : "STOP").append(" | ");
                md.append(s.getStatus()).append(" | ");
                md.append(s.getSummary() != null ? s.getSummary() : "").append(" |\n");
            }
        }

        md.append("\n## Patch Guard\n\n");
        md.append("- **passed:** ").append(c.isPatchGuardPassed()).append("\n");
        md.append("- **patchApplied:** ").append(c.isPatchApplied()).append("\n");
        md.append("- **compilePassed:** ").append(c.isCompilePassed()).append("\n");
        md.append("- **testsPassed:** ").append(c.isTestsPassed()).append("\n");
        md.append("- **realEvidenceCoverage:** ").append((int) (c.getRealEvidenceCoverage() * 100)).append("%\n");
        md.append("- **fixtureEvidenceUsed:** ").append(c.isFixtureEvidenceUsed()).append("\n");
        if (c.getPatchSandbox() != null && !c.getPatchSandbox().isEmpty()) {
            md.append("- **patchSandbox:** ").append(c.getPatchSandbox().getOrDefault("mode", ""))
                    .append(", isolated=").append(c.getPatchSandbox().getOrDefault("isolated", false)).append("\n");
        }
        if (c.getPatchQuality() != null && !c.getPatchQuality().isEmpty()) {
            md.append("- **patchQuality:** minimalChangeScore=")
                    .append(c.getPatchQuality().getOrDefault("minimalChangeScore", ""))
                    .append(", staticSafetyPassed=")
                    .append(c.getPatchQuality().getOrDefault("staticSafetyPassed", "")).append("\n");
        }

        if (c.getFailureType() != null && !c.getFailureType().isEmpty()) {
            md.append("- **failureType:** ").append(c.getFailureType()).append("\n");
            md.append("- **failureSummary:** ").append(c.getFailureSummary()).append("\n");
        }

        if (c.getReflectionRounds() > 0 && c.getReflectionHistory() != null) {
            md.append("\n## Reflection History\n\n");
            md.append("| Round | Skill | FailureType | MustFix | Recovered |\n|---|---|---|---|---|\n");
            for (CodeOpsReflectionReport r : c.getReflectionHistory()) {
                md.append("| ").append(r.getRound()).append(" | ");
                md.append(r.getFailedSkill()).append(" | ");
                md.append(r.getFailureType()).append(" | ");
                md.append(String.join("; ", r.getMustFix())).append(" | ");
                md.append(r.isRecovered() ? "YES" : "NO").append(" |\n");
            }
        }

        md.append("\n## Release Risk\n\n");
        md.append("- **riskLevel:** ").append(c.getFinalRiskLevel() != null ? c.getFinalRiskLevel() : "N/A").append("\n");
        md.append("- **releaseRiskGenerated:** ").append(c.isReleaseRiskGenerated()).append("\n");

        return md.toString();
    }

    private String pct(BigDecimal value) {
        if (value == null) return "N/A";
        return value.multiply(BigDecimal.valueOf(100)).intValue() + "%";
    }

    private String yn(boolean value) {
        return value ? "YES" : "NO";
    }

    private String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        return "[\"" + String.join("\", \"", items) + "\"]";
    }
}
