package com.opsautoagent.domain.codeops.agent.localization;

import com.alibaba.fastjson.JSON;

public final class CodeLocalizationPrompts {

    private CodeLocalizationPrompts() {
    }

    public static String buildPrompt(CodeLocalizationAgentInput input) {
        return String.format("""
                You are a senior Java backend incident triage agent.

                Your job is to triage an incident before any code patch is generated. Decide whether this incident
                should enter automatic code repair, then locate suspicious files and methods only when code repair is justified.

                Important rules:
                - Output only JSON.
                - Use only files, methods, snippets, and tests present in the input.
                - Search results are candidates, not conclusions. Rank them by how well they explain the incident.
                - Do not send pure runtime/config/capacity incidents into code repair unless evidence points to source code.
                - Use strategyType CODE_FIX only when exceptions, stack frames, source symbols, leaks, slow SQL, or code snippets explain the incident.
                - Use CONFIG_FIX for Hikari/connection pool/timeouts/config tuning without code evidence.
                - Use RUNTIME_ACTION for JVM GC, OOM, CPU, heap, thread dump, or runtime resource symptoms without code evidence.
                - Use CAPACITY_FIX for traffic saturation, queue pressure, instance capacity, or scaling symptoms.
                - Use NEED_MORE_EVIDENCE when evidence is not enough.
                - Prefer precise file and method localization over broad module guesses.
                - If the evidence is insufficient, keep confidence LOW and list missingEvidence.
                - Do not generate a patch. This agent only localizes code.

                Return JSON matching this schema:
                {
                  "confidence": "LOW|MEDIUM|HIGH",
                  "strategyType": "CODE_FIX|CONFIG_FIX|RUNTIME_ACTION|CAPACITY_FIX|RUNBOOK_FIX|NEED_MORE_EVIDENCE",
                  "shouldEnterCodeRepair": true,
                  "targetFiles": ["string"],
                  "targetMethods": ["string"],
                  "suspiciousLocations": ["string"],
                  "relatedTests": ["string"],
                  "reasoning": ["string"],
                  "missingEvidence": ["string"]
                }

                Code localization input:
                %s
                """, JSON.toJSONString(input));
    }

}
