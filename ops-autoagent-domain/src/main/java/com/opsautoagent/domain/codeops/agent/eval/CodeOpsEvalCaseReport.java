package com.opsautoagent.domain.codeops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsEvalCaseReport {

    private String caseId;
    private String caseName;
    private String status;
    private String taskId;
    private String taskType;
    private String scopeType;
    private List<String> targetMethods;
    private List<String> selectedSkills;
    private int stepCount;
    private long latencyMs;
    private boolean patchGenerated;
    private boolean patchGuardPassed;
    private boolean patchApplied;
    private boolean compilePassed;
    private boolean testsPassed;
    private int reflectionRounds;
    private boolean reflectionRecovered;
    private boolean releaseRiskGenerated;
    private String finalRiskLevel;
    private String failureType;
    private String failureSummary;
    private List<CodeOpsEvalStepReport> steps;
    private List<CodeOpsReflectionReport> reflectionHistory;
    private ReportArtifacts artifacts;
}
