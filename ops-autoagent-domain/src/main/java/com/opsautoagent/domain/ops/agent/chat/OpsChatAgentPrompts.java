package com.opsautoagent.domain.ops.agent.chat;

import com.alibaba.fastjson.JSON;

import java.util.List;

public final class OpsChatAgentPrompts {

    private OpsChatAgentPrompts() {
    }

    public static OpsChatAgentDefinition definition(OpsChatAgentRole role) {
        return OpsChatAgentDefinition.builder()
                .role(role)
                .agentName(role.getDescription())
                .systemPrompt(systemPrompt(role))
                .outputSchema(outputSchema(role))
                .requiredInputs(requiredInputs(role))
                .allowedTools(allowedTools(role))
                .build();
    }

    public static String systemPrompt(OpsChatAgentRole role) {
        return switch (role) {
            case PLANNER -> """
                    You are the Planner Agent for an ops incident diagnosis system.
                    Decide which evidence should be collected next. Output only structured JSON.
                    Never claim root cause. Prefer minimal tool calls and explain evidence gaps.
                    Historical incident memory is prior experience only. Use it to prioritize evidence collection, not as current evidence.
                    """;
            case EVIDENCE_REVIEWER -> """
                    You are the Evidence Reviewer Agent for an ops incident diagnosis system.
                    Review neutral evidence signals and retrieved Runbook/RAG context.
                    Historical incident memory may provide similar past cases, but it is not direct evidence for the current incident.
                    The caller does not provide a pre-made root-cause hypothesis. You must first classify evidence semantics, then decide whether the evidence supports a root cause.
                    Do not rely on hard-coded metric names. Use evidence content, Runbook/RAG failure patterns, and negative evidence to classify each signal.
                    Classify each signal as one of SYMPTOM, ROOT_CAUSE_INDICATOR, CONTRIBUTING_FACTOR, NEGATIVE_EVIDENCE, CONTEXT, or UNKNOWN.
                    Do not claim a root cause unless metrics/logs/traces and Runbook context support it.
                    Treat NO_ANOMALY/OK from a successfully collected source as negative evidence, not missing evidence.
                    Request supplemental collection only for UNAVAILABLE, NO_DATA, UNKNOWN, skipped, stale, or entity/time-window-mismatched sources.
                    Do not request the same ELK/SkyWalking/Prometheus tool again merely because it returned no abnormal records.
                    Decide SUFFICIENT when the investigation can stop. This may be ROOT_CAUSE_CONFIRMED, PROBABLE_ROOT_CAUSE, or INVESTIGATION_COMPLETE_ROOT_CAUSE_UNRESOLVED.
                    Use ROOT_CAUSE_CONFIRMED when a specific root cause is supported by either multi-source evidence or a root-cause-specific single source signal.
                    A generic symptom metric such as 5xx rate confirms the failure symptom, not the underlying root cause by itself.
                    A root-cause-specific signal such as Hikari pool exhaustion, GC pause spike, CPU saturation, thread pool saturation, or connection timeout can support a confirmed root cause even if it comes from one primary telemetry source, provided it has no contradiction and no collectable evidence gap.
                    Use PROBABLE_ROOT_CAUSE when the symptom is confirmed, Runbook/RAG matches a likely failure mode, key sources were checked, and negative evidence narrows the likely direction, but the evidence does not fully confirm a concrete root cause.
                    Use INVESTIGATION_COMPLETE_ROOT_CAUSE_UNRESOLVED when the symptom is confirmed and enough sources were checked, but negative evidence prevents a specific root-cause claim.
                    For unresolved conclusions, leave rootCause empty or use a cautious "specific root cause not confirmed" statement; do not invent a concrete cause.
                    For PROBABLE_ROOT_CAUSE, provide a useful rootCause with medium confidence and explain what was ruled out by NO_ANOMALY/OK evidence.
                    Decide NEED_MORE_EVIDENCE only when there is a real collectable evidence gap, not when a queried source returned NO_ANOMALY.
                    Decide SUFFICIENT only when the sufficiency rubric passes for the selected conclusion: direct evidence, source coverage, temporal alignment, entity alignment, Runbook support, no unresolved contradiction, and no collectable evidence gap.
                    Output only structured JSON. Separate confirmed facts from hypotheses.
                    """;
            case REPORT_WRITER -> """
                    You are the Report Writer Agent for an ops incident diagnosis system.
                    Write a concise incident report constrained by confirmed evidence.
                    Distinguish confirmed root cause, probable root cause, and unresolved root cause.
                    For PROBABLE_ROOT_CAUSE, write a useful likely diagnosis with confidence and explain which alternatives were weakened by negative evidence.
                    Do not invent facts. Runbooks support remediation suggestions only. Historical memories can be cited only as similar past incidents.
                    """;
        };
    }

    public static String outputSchema(OpsChatAgentRole role) {
        return switch (role) {
            case PLANNER -> """
                    {
                      "alertType": "string",
                      "hypotheses": ["string"],
                      "requiredTools": ["query_prometheus|query_elasticsearch|query_skywalking_trace|query_runbook"],
                      "steps": [{"stepId":"string","toolName":"string","queryIntent":"string","successCriteria":"string"}],
                      "expectedEvidence": ["string"],
                      "riskLevel": "LOW|MEDIUM|HIGH",
                      "rationale": "string"
                    }
                    """;
            case EVIDENCE_REVIEWER -> """
                    {
                      "status": "SUFFICIENT|NEED_MORE_EVIDENCE|INSUFFICIENT_FINAL",
                      "sufficient": true,
                      "confidenceScore": 0,
                      "confirmedFacts": ["string"],
                      "weakEvidence": ["string"],
                      "missingEvidence": ["string"],
                      "requiredTools": ["string"],
                      "reportConstraints": ["string"],
                      "evidenceSemantics": [{
                        "signalId": "string",
                        "source": "string",
                        "signalName": "string",
                        "semanticType": "SYMPTOM|ROOT_CAUSE_INDICATOR|CONTRIBUTING_FACTOR|NEGATIVE_EVIDENCE|CONTEXT|UNKNOWN",
                        "causalLevel": "failure_symptom|failure_mode|resource_bottleneck|dependency_failure|application_failure|observability_gap|unknown",
                        "specificity": "generic|specific",
                        "evidenceStrength": 0,
                        "supports": ["string"],
                        "weakens": ["string"],
                        "reasoning": "string"
                      }],
                      "sufficiency": {
                        "directEvidence": true,
                        "multiSourceSupport": true,
                        "sourceCoverage": true,
                        "rootCauseSupport": true,
                        "rootCauseSpecificEvidence": true,
                        "negativeEvidenceConsidered": true,
                        "collectableEvidenceGap": false,
                        "temporalAlignment": true,
                        "entityAlignment": true,
                        "runbookSupport": true,
                        "noContradiction": true,
                        "missingCriticalEvidence": ["string"],
                        "contradictions": ["string"],
                        "rationale": "string"
                      },
                      "conclusionType": "ROOT_CAUSE_CONFIRMED|PROBABLE_ROOT_CAUSE|INVESTIGATION_COMPLETE_ROOT_CAUSE_UNRESOLVED|NEED_MORE_EVIDENCE",
                      "rootCause": "string",
                      "rootCauseCategory": "string",
                      "rootCauseConfidence": 0,
                      "rootCauseRationale": "string",
                      "candidateRootCauses": ["string"],
                      "rationale": "string"
                    }
                    """;
            case REPORT_WRITER -> """
                    {
                      "title": "string",
                      "summary": "string",
                      "dataCollectionStatus": ["string"],
                      "rootCauseCandidates": ["string"],
                      "missingEvidence": ["string"],
                      "temporaryMitigation": ["string"],
                      "longTermImprovements": ["string"],
                      "finalReportMarkdown": "string"
                    }
                    """;
        };
    }

    public static String buildPrompt(OpsChatAgentInput input) {
        OpsChatAgentRole role = input.getRole();
        return String.format("""
                %s

                Return JSON matching this schema:
                %s

                Agent input:
                %s
                """,
                systemPrompt(role),
                outputSchema(role),
                JSON.toJSONString(input));
    }

    private static List<String> requiredInputs(OpsChatAgentRole role) {
        return switch (role) {
            case PLANNER -> List.of("incidentContext", "incidentStateJson", "workingMemoryJson", "historicalMemoryJson", "toolConstraintsJson", "metadata");
            case EVIDENCE_REVIEWER -> List.of("incidentContext", "workingMemoryJson", "historicalMemoryJson", "toolConstraintsJson", "planJson", "evidenceJson", "reviewerResultJson");
            case REPORT_WRITER -> List.of("incidentContext", "workingMemoryJson", "historicalMemoryJson", "toolConstraintsJson", "evidenceJson", "reviewerResultJson", "runbookJson");
        };
    }

    private static List<String> allowedTools(OpsChatAgentRole role) {
        return switch (role) {
            case PLANNER -> List.of("query_prometheus", "query_elasticsearch", "query_skywalking_trace", "query_runbook");
            case EVIDENCE_REVIEWER -> List.of("query_prometheus", "query_elasticsearch", "query_skywalking_trace", "query_runbook");
            case REPORT_WRITER -> List.of();
        };
    }

}

