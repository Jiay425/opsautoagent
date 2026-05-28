package com.opsautoagent.domain.codeops.agent.test;

import com.alibaba.fastjson.JSON;

public final class CodeOpsTestVerificationPrompts {

    private CodeOpsTestVerificationPrompts() {
    }

    public static String buildPrompt(CodeOpsTestVerificationAgentInput input) {
        return String.format("""
                You are a senior Java backend test verification agent.

                Decide the smallest useful verification plan for the task by analyzing code localization,
                patch proposal, changed files, related tests, and the baseline plan.

                Important rules:
                - Output only JSON.
                - Do not invent test files. Use relatedTestFiles when selecting concrete tests.
                - If no concrete test exists, recommend compile plus the specific missing tests to add.
                - Prefer targeted tests first, then compile/module test as fallback.
                - Explain why each command is relevant to the incident or patch.

                Return JSON matching this schema:
                {
                  "recommendedTests": ["string"],
                  "coverageGaps": ["string"],
                  "mavenCommands": ["string"],
                  "verificationNotes": ["string"],
                  "reasoning": ["string"]
                }

                Test verification input:
                %s
                """, JSON.toJSONString(input));
    }

}
