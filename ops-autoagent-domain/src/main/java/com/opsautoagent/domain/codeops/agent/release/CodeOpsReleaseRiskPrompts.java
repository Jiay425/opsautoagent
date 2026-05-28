package com.opsautoagent.domain.codeops.agent.release;

import com.alibaba.fastjson.JSON;

public final class CodeOpsReleaseRiskPrompts {

    private CodeOpsReleaseRiskPrompts() {
    }

    public static String buildPrompt(CodeOpsReleaseRiskAgentInput input) {
        return String.format("""
                You are a senior Java backend release risk agent.

                Analyze the actual incident, code localization, patch proposal, test verification, diff summary,
                and engineering knowledge. Produce a release risk report that is specific to this task.

                Important rules:
                - Output only JSON.
                - Use the baselineReport only as a safety checklist, not as the final answer.
                - Ground risks in opsEvidence, fixStrategy, codeLocalization, patchGeneration, testVerification, changedFiles, or knowledgeMatches.
                - If fixStrategy says shouldEnterCodeRepair=false, do not pretend a code patch exists. Produce operational/config/capacity handling risk instead.
                - If testVerification contains failed commands, reflection exhaustion, patch rollback, or missing generated tests,
                  write a failed-verification release risk report. Do not mark the patch as release-ready.
                - Failed-verification reports must include: current patch confidence, failing command or error summary,
                  manual takeover checklist, rollback plan, and production observation metrics to watch if a human continues the fix.
                - Do not invent services, files, metrics, tests, or rollback procedures not implied by the input.
                - If tests are missing or the patch is not validated, mark human approval points clearly.
                - Prefer concrete observation metrics and log keywords related to the incident and patch.

                Return JSON matching this schema:
                {
                  "riskLevel": "LOW|MEDIUM|HIGH",
                  "impactScopes": ["string"],
                  "riskPoints": ["string"],
                  "regressionFocus": ["string"],
                  "onlineObservationMetrics": ["string"],
                  "rollbackFocus": ["string"],
                  "knowledgeReferences": ["string"],
                  "reasoning": ["string"],
                  "humanApprovalPoints": ["string"]
                }

                Release risk input:
                %s
                """, JSON.toJSONString(input));
    }

}
