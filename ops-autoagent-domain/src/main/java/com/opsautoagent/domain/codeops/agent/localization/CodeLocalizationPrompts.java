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
                - Use evidenceGraph when present. Treat graph nodes as alert/log/trace/metric/code/test evidence,
                  and graph edges as why one evidence item points to a code location.
                - A method/class that is absent from the alert can still become a candidate only if the evidenceGraph
                  or codeSnippets show a concrete code relation, such as a caller invoking a helper/service/repository.
                - In reasoning, cite the evidence chain: incident signal -> search/snippet -> code method/file.
                - Do not send pure runtime/config/capacity incidents into code repair unless evidence points to source code.
                - Use strategyType CODE_FIX only when exceptions, stack frames, source symbols, leaks, slow SQL, or code snippets explain the incident.
                - Use strategyType CODE_FIX for duplicate requestId accepted successfully, idempotency race, check-then-act race,
                  non-atomic state mutation, duplicate order creation, negative stock caused by concurrency, or data consistency bugs.
                - Use CONFIG_FIX for Hikari/connection pool/timeouts/config tuning without code evidence.
                - Use RUNTIME_ACTION for JVM GC, OOM, CPU, heap, thread dump, or runtime resource symptoms without code evidence.
                - Use CAPACITY_FIX for traffic saturation, queue pressure, instance capacity, or scaling symptoms.
                - Use NEED_MORE_EVIDENCE when evidence is not enough.
                - Prefer precise file and method localization over broad module guesses.
                - Do not lock the final repair boundary. This agent only proposes an initial suspect and a safe candidate boundary.
                - Put the most likely method in primarySuspectMethod. Put every evidence-related method that may need a coordinated fix
                  in candidateMethods, even if only one method is currently the primary suspect.
                - scopeSuggestion is your initial estimate, not a hard lock. Use STRICT_SINGLE_METHOD only when the incident clearly
                  affects one method. Use MULTI_METHOD when callers, validators, repository calls, idempotency, locking, or shared helpers
                  may need to change together. Use FULL_FILE only when method-level localization is not reliable.
                - expandable=true means the Code Repair Agent may broaden from the primary suspect to candidateMethods if visible code
                  proves the one-method fix is insufficient. expansionBoundary must list the files/methods that are allowed for such broadening.
                - If the evidence is insufficient, keep confidence LOW and list missingEvidence.
                - Do not generate a patch. This agent only localizes code.

                Return JSON matching this schema:
                {
                  "confidence": "LOW|MEDIUM|HIGH",
                  "strategyType": "CODE_FIX|CONFIG_FIX|RUNTIME_ACTION|CAPACITY_FIX|RUNBOOK_FIX|NEED_MORE_EVIDENCE",
                  "shouldEnterCodeRepair": true,
                  "targetFiles": ["string"],
                  "targetMethods": ["string"],
                  "primarySuspectMethod": "ClassName.methodName",
                  "candidateFiles": ["string"],
                  "candidateMethods": ["ClassName.methodName"],
                  "scopeSuggestion": "STRICT_SINGLE_METHOD|MULTI_METHOD|FULL_FILE|NO_CODE_FIX",
                  "scopeConfidence": "LOW|MEDIUM|HIGH",
                  "expandable": true,
                  "expansionBoundary": ["file-or-method string"],
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
