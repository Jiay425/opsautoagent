package com.opsautoagent.domain.codeops.agent.bugfix;

import com.alibaba.fastjson.JSON;

public final class CodeOpsBugFixPrompts {

    private CodeOpsBugFixPrompts() {
    }

    /**
     * Build a prominent, hard-to-miss reflection block that appears BEFORE the JSON input.
     * LLMs give more attention to plain text at the top than to fields buried in JSON.
     */
    private static String buildReflectionBlock(CodeOpsBugFixAgentInput input) {
        if (input == null || input.getReflectionDiagnostics() == null
                || input.getReflectionDiagnostics().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n!!! REFLECTION ROUND — PREVIOUS ATTEMPT(S) FAILED !!!\n");
        sb.append("The following failures occurred. Your new patch MUST fix them.\n\n");

        int roundNum = 1;
        for (Object diag : input.getReflectionDiagnostics()) {
            if (!(diag instanceof java.util.Map<?, ?> m)) continue;

            Object ft = m.get("failureType");
            String failureType = ft != null ? String.valueOf(ft) : "UNKNOWN";
            sb.append("  Round ").append(roundNum).append(" FAILED: ").append(failureType).append("\n");

            Object mustFix = m.get("mustFix");
            if (mustFix instanceof java.util.List<?> mfl) {
                for (Object item : mfl) {
                    sb.append("    MUST FIX: ").append(item).append("\n");
                }
            }

            Object mustAvoid = m.get("mustAvoid");
            if (mustAvoid instanceof java.util.List<?> mal) {
                for (Object item : mal) {
                    sb.append("    MUST AVOID: ").append(item).append("\n");
                }
            }

            Object constraints = m.get("nextAttemptConstraints");
            if (constraints instanceof java.util.List<?> cl) {
                for (Object item : cl) {
                    sb.append("    CONSTRAINT: ").append(item).append("\n");
                }
            }

            Object repairScope = m.get("repairScope");
            if (repairScope instanceof java.util.Map<?, ?> rs) {
                sb.append("    SCOPE: ").append(rs.get("scopeType"))
                        .append(", methods: ").append(rs.get("targetMethods")).append("\n");
            }

            roundNum++;
        }

        sb.append("\n!!! END REFLECTION — GENERATE A DIFFERENT PATCH !!!\n");
        return sb.toString();
    }

    public static String buildPrompt(CodeOpsBugFixAgentInput input) {
        String reflectionBlock = buildReflectionBlock(input);
        return String.format("""
                You are a senior Java backend incident-fix agent.

                Your task is to analyze a production incident from telemetry evidence and real repository code snippets,
                then propose the smallest safe production fix and the minimal regression test plan in one response.

                Important rules:
                - Output only JSON.
                - Ground every claim in the provided opsDiagnosis, codeContextPack, codeSearchMatches, codeSnippets, or knowledgeMatches.
                - Prefer codeContextPack over raw search hits. codeContextPack is the curated repository context:
                  primaryFiles are the first repair suspects, candidateFiles are legal expansion files, supportFiles/tests/buildFiles explain dependencies.
                  contextReasons explains why each file was included.
                - If codeContextPack shows a state-owning component, repository, cache, lock, idempotency service, or related test,
                  reason over that component before choosing a caller-only patch.
                - If memoryHints is not empty, treat them as REFERENCE ONLY — they describe what worked for similar past incidents.
                  Use them only if they are consistent with current evidence, repairScope and visible code.
                  Never override current incident evidence with memory.
                  Memory hints are suggestions, not facts.
                - Do not invent files, methods, fields, dependencies, APIs, metrics, logs, or line numbers not present in the input.
                - repairPlan is the Plan-and-Execute contract from Code Localization Agent. Treat it as the primary execution plan:
                  * fixStrategy decides whether a code patch is appropriate.
                  * scopeDecision describes the expected repair boundary.
                  * targetFiles/targetMethods are the intended first repair targets.
                  * candidateFiles/candidateScope are legal expansion boundaries, not a license to rewrite unrelated code.
                  * verificationPlan and riskPlan must be reflected in testSuggestions, mavenCommands, and riskNotes.
                  * If repairPlan conflicts with visible code or reflectionDiagnostics, explain in reasoning and prefer current evidence plus diagnostics.
                - CRITICAL — repairScope has TWO stages:
                  * initialScope is the Code Localization Agent's best first suspect. It is a recommendation, not a final lock.
                  * candidateScope is the maximum safe boundary derived from evidence and repository search. You may not go outside it.
                  * Your response MUST include scopeDecision. Choose KEEP_SCOPE when initialScope is enough. Choose EXPAND_SCOPE only when
                    the visible code proves that fixing the primary method alone cannot satisfy the incident requirement.
                  * If you choose EXPAND_SCOPE, finalTargetMethods/finalTargetFiles must be a subset of candidateScope. Explain why each added
                    method is required by current evidence or direct code coupling.
                  * If the fix must add a new method, change several helper methods in the same candidate file, or replace a check-then-act API
                    with an atomic API, use finalScopeType=FULL_FILE with finalTargetFiles limited to candidateScope.targetFiles and leave
                    finalTargetMethods empty. This is allowed only inside candidateScope files.
                - Scope execution rules after your scopeDecision:
                  * STRICT_SINGLE_METHOD: Fix ONLY finalTargetMethods. All other methods in the same file MUST be preserved byte-for-byte from codeContextPack/codeSnippets.
                  * MULTI_METHOD: Fix only finalTargetMethods and necessary signature/import adjustments. Other methods must remain untouched.
                  * FULL_FILE: The incident may require broader changes inside finalTargetFiles. Still prefer minimal changes and preserve normal behavior.
                  * NO_CODE_FIX: This is NOT a code repair incident. Do NOT generate any patch or file rewrite. Output empty unifiedDiffPatch, empty fileRewrites, empty testFileRewrites.
                - PATCH FORMAT PRIORITY:
                  1. Prefer fileRewrites for every production Java change.
                  2. Use exactReplaceBlocks when the visible source contains a precise oldText block and a full file rewrite is unnecessary.
                  3. Keep unifiedDiffPatch empty when fileRewrites or exactReplaceBlocks is present.
                  4. Use unifiedDiffPatch only when you cannot safely reconstruct complete file content from visible snippets.
                  4. For STRICT_SINGLE_METHOD / MULTI_METHOD / FULL_FILE Java repairs, fileRewrites is the default expected output because ScopeGuard can validate complete files more reliably than handwritten unified diffs.
                - For fileRewrites, the newContent MUST contain the COMPLETE file including all unchanged methods. Copy non-target methods VERBATIM from codeContextPack/codeSnippets.
                - For exactReplaceBlocks, oldText MUST be copied byte-for-byte from visible codeContextPack/codeSnippets. If oldText does not match the current file, the edit will fail and the next reflection round must re-read the file.
                - Before outputting, self-check: (1) Did I choose KEEP_SCOPE or EXPAND_SCOPE? (2) Are final targets inside candidateScope?
                  (3) Did I preserve non-target methods exactly? Redo if any answer is wrong.
                - For INCIDENT_TO_FIX tasks with CODE_FIX strategy, put the complete rewritten production file content in fileRewrites.
                - This combined Code Repair & Test Agent owns both production fix and test proposal for INCIDENT_TO_FIX.
                - If writing an exact unified diff is difficult, put the complete rewritten file content in fileRewrites instead.
                - If you output only unifiedDiffPatch for a Java production change while complete file content is visible, the patch may be rejected by ScopeGuard as UNIFIED_DIFF_ONLY.
                - If the provided snippets are insufficient to create either a safe patch or a full file rewrite, output an empty unifiedDiffPatch and an empty fileRewrites array.
                - Prefer a minimal patch over broad refactoring.
                - Preserve existing behavior for normal requests.
                - For cross-file check-then-act races, fix the component that owns the mutable state. Do NOT only add a synchronized block
                  around the caller when a candidate service/repository owns the set, map, stock, cache, or idempotency state.
                  Example: if OrderSubmitService calls IdempotencyService.alreadyProcessed() and IdempotencyService.markProcessed()
                  separately, the robust fix is to add/use an atomic method in IdempotencyService and call it from OrderSubmitService.
                  This requires EXPAND_SCOPE and usually finalScopeType=FULL_FILE over both candidate files.
                - Do not propose direct production deployment. The patch is a draft for human review.
                - If unifiedDiffPatch is used, it must be a valid unified diff that can be applied by `git apply`.
                - Unified diff rules: use standard headers exactly like `--- a/path` and `+++ b/path`, followed by a real `@@ -old,count +new,count @@` hunk header.
                - Unified diff context lines must be copied exactly from codeSnippets, including indentation.
                - Unified diff unchanged context lines must start with one space, removed lines with `-`, and added lines with `+`.
                - Do not wrap patches in markdown fences and do not prefix them with prose such as PATCH_PROPOSAL_DRAFT.
                - The patch or fileRewrites must touch an existing production file from codeSnippets or codeSearchMatches.
                - If a concrete test can be written using provided snippets and existing project style, put it in testFileRewrites or testUnifiedDiffPatch.
                - For INCIDENT_TO_FIX with a CODE_FIX decision, do not stop at testSuggestions when the repository is a Maven/JUnit project.
                  Create at least one concrete JUnit 5 test file in testFileRewrites unless the input truly lacks constructor/API details.
                - Name new test files after the behavior under repair, not generic names. For concurrency or idempotency incidents, prefer
                  names such as InventoryConcurrencyTest and OrderSubmitServiceConcurrencyTest when those source classes are present.
                - Every testFileRewrites entry must contain complete compilable Java source, including package, imports, class declaration and @Test methods.
                - Use only dependencies already present in the repository build file. If Mockito is not present, do not import org.mockito or use @Mock.
                - Use only constructors and methods visible in provided codeSnippets. Do not invent methods such as tryMarkProcessed, clearProcessed,
                  releaseStock, rollback, or repository helpers unless those methods are visible in the current codeSnippets.
                - Preserve visible public method signatures and constructor signatures. When calling an existing method, match the exact parameter
                  count, order, and types shown in codeSnippets.
                - Do not remove request fields or reorder repository calls unless the incident evidence directly requires it and all call sites still compile.
                - For simple sample services, prefer in-memory fake repositories or direct real collaborators over mocking frameworks.
                - In the order-service sample, InventoryRepository and OrderRepository are concrete classes, not interfaces.
                  Never write `implements InventoryRepository` or `implements OrderRepository`.
                - In the order-service sample, OrderSubmitRequest constructors are:
                  `OrderSubmitRequest(String userId, String skuId, Integer quantity, BigDecimal unitPrice)` and
                  `OrderSubmitRequest(String userId, String skuId, String requestId, Integer quantity, BigDecimal unitPrice)`.
                - testSuggestions must include the exact test class names and the behavior each test proves.
                - Maven commands must include:
                  1. mvn -q -DskipTests compile
                  2. mvn -q -Dtest=<comma-separated generated/existing test class names> test
                  3. mvn -q test
                - If the exact test file is unknown, explain which constructor/API detail is missing in testSuggestions and keep test patch empty.
                - Include Maven commands to run after applying patches. Prefer targeted tests first, then module test.
                - If reflectionDiagnostics is not empty, this is a REFLECTION RETRY round. Treat diagnostics as mandatory reviewer feedback:
                  * Step 1: Read failureType and understand WHY the previous attempt failed.
                  * Step 2: Read mustFix — these are HARD requirements the new patch MUST satisfy.
                  * Step 3: Read mustAvoid — these are HARD prohibitions the new patch MUST NOT violate.
                  * Step 4: Read nextAttemptConstraints — these are non-negotiable constraints.
                  * The new patch MUST directly address the failureType.
                - FailureType-specific guidance:
                  * SCOPE_GUARD_FAILED: Read the guard violation. If you need to add a new method or update multiple helper methods inside
                    candidateScope.targetFiles, output EXPAND_SCOPE with finalScopeType=FULL_FILE, finalTargetFiles limited to candidateScope,
                    and finalTargetMethods empty. If it is outside candidateScope, do not touch it.
                  * PATCH_APPLY_FAILED: Use fileRewrites with COMPLETE file content instead of unifiedDiffPatch. Leave unifiedDiffPatch empty.
                  * COMPILE_FAILED: Fix the exact compiler error BEFORE changing any logic. Read failedCommands and failedFiles for clues.
                  * SOURCE_STRUCTURE_INVALID: Fix unbalanced braces or malformed Java syntax. Check sourceValidation.errors.
                  * TEST_COMPILE_FAILED: First decide whether the failing test expresses the incident requirement. If the test requires a missing
                    production API such as IdempotencyService.tryMarkProcessed(String), implement the production API and update callers.
                    Do NOT remove or weaken a valid incident regression test.
                  * TEST_ASSERTION_FAILED: If the test reflects the incident requirement, adjust production code. Otherwise adjust the test.
                  * TEST_PATCH_APPLY_FAILED: Ensure test files exist in correct src/test directories with valid package/imports.
                  * TEST_TIMEOUT: Ensure tests have bounded execution time. Do not use unbounded waits.
                - In every round, prefer complete fileRewrites over handwritten unifiedDiffPatch for production Java files.
                  Keep unifiedDiffPatch empty when fileRewrites is present.
                - In reflection rounds, broaden the fix only through scopeDecision=EXPAND_SCOPE and only inside repairScope.candidateScope.
                  Otherwise change ONLY the files listed in reflectionDiagnostics.failedFiles or the final repair scope.
                - The new patch must be DIFFERENT from the previous failed attempt. Do not resubmit the same patch.

                Return JSON matching this schema:
                {
                  "rootCause": "string",
                  "confidence": "LOW|MEDIUM|HIGH",
                  "targetFiles": ["string"],
                  "reasoning": ["string"],
                  "reflectionDiagnosis": {
                    "failureType": "NONE|SOURCE_STRUCTURE_INVALID|COMPILE_ERROR|TEST_COMPILE_ERROR|TEST_TIMEOUT|TEST_ASSERTION_FAILED|PATCH_APPLY_FAILED|UNKNOWN",
                    "failedFiles": ["string"],
                    "mustFix": ["string"],
                    "mustAvoid": ["string"]
                  },
                  "scopeDecision": {
                    "decision": "KEEP_SCOPE|EXPAND_SCOPE",
                    "finalScopeType": "STRICT_SINGLE_METHOD|MULTI_METHOD|FULL_FILE|NO_CODE_FIX",
                    "finalTargetFiles": ["string"],
                    "finalTargetMethods": ["ClassName.methodName"],
                    "whyKeepOrExpand": ["string"],
                    "expectedBehaviorChange": "string",
                    "risk": "LOW|MEDIUM|HIGH"
                  },
                  "unifiedDiffPatch": "empty string when fileRewrites is present; unified diff only as fallback",
                  "fileRewrites": [{"filePath": "string", "newContent": "complete file content", "reasoning": "string"}],
                  "exactReplaceBlocks": [{"filePath": "string", "oldText": "exact visible source block", "newText": "replacement source block", "reasoning": "string"}],
                  "testSuggestions": ["string"],
                  "mavenCommands": ["string"],
                  "testUnifiedDiffPatch": "string",
                  "testFileRewrites": [{"filePath": "string", "newContent": "complete file content", "reasoning": "string"}],
                  "riskNotes": ["string"]
                }

                %s

                Incident fix input:
                %s
                """, reflectionBlock, JSON.toJSONString(input));
    }

}
