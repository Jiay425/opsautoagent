package com.opsautoagent.domain.ops.agent.review;

import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentInput;
import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentJsonSupport;
import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentOutput;
import com.opsautoagent.domain.ops.agent.chat.OpsMultiChatAgentService;
import com.opsautoagent.domain.ops.agent.memory.OpsHistoricalIncidentMemoryService;
import com.opsautoagent.domain.ops.agent.memory.OpsIncidentWorkingMemory;
import com.opsautoagent.domain.ops.agent.memory.OpsIncidentWorkingMemoryService;
import com.opsautoagent.domain.ops.agent.plan.OpsInvestigationPlan;
import com.opsautoagent.domain.ops.model.entity.EvidenceSemanticEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.LogEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.MetricEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.RootCauseCandidateEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import com.opsautoagent.domain.ops.model.entity.TraceEvidenceEntity;
import com.opsautoagent.domain.ops.service.execute.DefaultOpsAgentExecuteStrategyFactory;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OpsEvidenceReviewerService {

    @Value("${ops.agent.reviewer.enabled:true}")
    private boolean reviewerEnabled;

    @Value("${ops.agent.reviewer.min-confidence:0.75}")
    private double minConfidence;

    @Value("${ops.agent.chat.enabled:false}")
    private boolean chatAgentEnabled;

    @Value("${ops.agent.chat.required:false}")
    private boolean chatAgentRequired;

    @Resource
    private OpsMultiChatAgentService opsMultiChatAgentService;

    @Resource
    private OpsIncidentWorkingMemoryService workingMemoryService;

    @Resource
    private OpsHistoricalIncidentMemoryService historicalIncidentMemoryService;

    public OpsEvidenceReviewResult review(IncidentCommandEntity command,
                                          DefaultOpsAgentExecuteStrategyFactory.DynamicContext context,
                                          int round,
                                          int maxRounds) {
        if (!reviewerEnabled) {
            return OpsEvidenceReviewResult.builder()
                    .status("BYPASSED")
                    .round(round)
                    .sufficient(true)
                    .confidenceScore(100)
                    .confirmedFacts(List.of("Evidence Reviewer Agent is disabled by configuration."))
                    .weakEvidence(List.of())
                    .missingEvidence(List.of())
                    .requiredTools(List.of())
                    .reportConstraints(List.of("Use the existing evidence chain and explicitly state unavailable data sources."))
                    .sufficiency(OpsEvidenceSufficiency.builder()
                            .directEvidence(true)
                            .multiSourceSupport(true)
                            .sourceCoverage(true)
                            .rootCauseSupport(true)
                            .rootCauseSpecificEvidence(true)
                            .negativeEvidenceConsidered(true)
                            .collectableEvidenceGap(false)
                            .temporalAlignment(true)
                            .entityAlignment(true)
                            .runbookSupport(true)
                            .noContradiction(true)
                            .missingCriticalEvidence(List.of())
                            .contradictions(List.of())
                            .rationale("Reviewer disabled by configuration.")
                            .build())
                    .rationale("Reviewer disabled.")
                    .build();
        }

        MetricEvidenceEntity metricEvidence = context.getMetricEvidence();
        LogEvidenceEntity logEvidence = context.getLogEvidence();
        TraceEvidenceEntity traceEvidence = context.getTraceEvidence();
        List<RunbookMatchEntity> runbookMatches = context.getRunbookMatches();

        List<String> confirmedFacts = new ArrayList<>();
        List<String> weakEvidence = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        Set<String> requiredTools = new LinkedHashSet<>();

        addSourceReview("Prometheus metrics", available(metricEvidence), summary(metricEvidence),
                "query_prometheus", confirmedFacts, missingEvidence, requiredTools);
        addSourceReview("ELK logs", available(logEvidence), summary(logEvidence),
                "query_elasticsearch", confirmedFacts, missingEvidence, requiredTools);
        addSourceReview("SkyWalking traces", available(traceEvidence), summary(traceEvidence),
                "query_skywalking_trace", confirmedFacts, missingEvidence, requiredTools);
        addSourceReview("Runbook patterns", runbookMatches != null && !runbookMatches.isEmpty(),
                runbookMatches == null || runbookMatches.isEmpty() ? "" : "matched patterns=" + runbookMatches.size(),
                "query_runbook", confirmedFacts, missingEvidence, requiredTools);

        int availableSources = availableSources(metricEvidence, logEvidence, traceEvidence);
        boolean enoughSources = availableSources >= 2;
        if (!enoughSources) {
            weakEvidence.add("Fewer than two external evidence sources are available.");
        }
        if (runbookMatches == null || runbookMatches.isEmpty()) {
            weakEvidence.add("No Runbook/RAG context is available for root-cause analysis.");
        }
        OpsEvidenceSufficiency baselineSufficiency = buildBaselineSufficiency(command,
                availableSources,
                runbookMatches != null && !runbookMatches.isEmpty(),
                missingEvidence,
                weakEvidence);

        boolean canSupplement = round < maxRounds && !requiredTools.isEmpty();
        boolean sufficient = false;
        String status;
        if (canSupplement) {
            status = "NEED_MORE_EVIDENCE";
        } else {
            status = "INSUFFICIENT_FINAL";
            requiredTools.clear();
            missingEvidence.add("Evidence Reviewer Chat Agent must decide root cause. Rule baseline cannot finalize a root cause by itself.");
        }

        OpsEvidenceReviewResult ruleResult = OpsEvidenceReviewResult.builder()
                .status(status)
                .round(round)
                .sufficient(sufficient)
                .confidenceScore(0)
                .confirmedFacts(confirmedFacts)
                .weakEvidence(weakEvidence)
                .missingEvidence(missingEvidence)
                .requiredTools(new ArrayList<>(requiredTools))
                .reportConstraints(buildReportConstraints(status, command))
                .sufficiency(baselineSufficiency)
                .candidateRootCauses(List.of())
                .rationale(buildRationale(status, availableSources, 0, round, maxRounds)
                        + "; ruleBaseline=NO_ROOT_CAUSE_DECISION")
                .build();
        if (!chatAgentEnabled) {
            return ruleResult;
        }
        OpsEvidenceReviewResult chatResult = tryReviewWithChatAgent(command, context, round, maxRounds, ruleResult);
        if (chatResult != null) {
            return chatResult;
        }
        if (chatAgentRequired) {
            throw new IllegalStateException("Evidence Reviewer Chat Agent is required but unavailable or returned invalid output.");
        }
        ruleResult.setRationale(ruleResult.getRationale() + "; chatAgent=RULE_BASED_FALLBACK, reason=Chat Agent unavailable or invalid output");
        return ruleResult;
    }

    private OpsEvidenceReviewResult tryReviewWithChatAgent(IncidentCommandEntity command,
                                                           DefaultOpsAgentExecuteStrategyFactory.DynamicContext context,
                                                           int round,
                                                           int maxRounds,
                                                           OpsEvidenceReviewResult fallbackResult) {
        OpsChatAgentOutput output = null;
        try {
            OpsInvestigationPlan plan = context == null ? null : context.getValue("ops_investigation_plan");
            OpsIncidentWorkingMemory workingMemory = workingMemoryService.refreshFromContext(command, context);
            String historicalMemoryJson = historicalIncidentMemoryService.toPromptJson(
                    historicalIncidentMemoryService.querySimilar(command, context, 3));
            OpsChatAgentInput input = OpsChatAgentInput.builder()
                    .diagnosisId(command.getDiagnosisId())
                    .sessionId(command.getSessionId())
                    .serviceName(command.getServiceName())
                    .objective("Review whether the collected evidence is sufficient. Return JSON only.")
                    .incidentContext(JSON.toJSONString(command))
                    .workingMemoryJson(workingMemoryService.toPromptJson(workingMemory))
                    .historicalMemoryJson(historicalMemoryJson)
                    .toolConstraintsJson(buildToolConstraintsJson(round, maxRounds))
                    .planJson(plan == null ? "" : plan.getPlanJson())
                    .evidenceJson(buildEvidenceJson(context))
                    .reviewerResultJson(JSON.toJSONString(fallbackResult))
                    .constraints(List.of(
                            "Only request supported tools.",
                            "First classify evidenceSemantics from neutral evidence signals and Runbook/RAG context.",
                            "Do not rely on hard-coded metric-name rules; explain semantics from evidence content and retrieved failure patterns.",
                            "Do not mark sufficient unless evidence supports a conclusion.",
                            "If collectable evidence is missing, keep status NEED_MORE_EVIDENCE.",
                            "If Prometheus confirms a symptom and ELK/SkyWalking are already collected as NO_ANOMALY/OK, do not return NEED_MORE_EVIDENCE for those same tools.",
                            "Use conclusionType PROBABLE_ROOT_CAUSE when the evidence supports a useful likely direction but does not fully confirm a concrete root cause.",
                            "If evidence sources were collected and negative evidence prevents a concrete root-cause claim, use status SUFFICIENT with conclusionType INVESTIGATION_COMPLETE_ROOT_CAUSE_UNRESOLVED.",
                            "Only use conclusionType ROOT_CAUSE_CONFIRMED when a concrete root cause is supported by multi-source evidence or by root-cause-specific single-source evidence.",
                            "A generic 5xx rate metric confirms the symptom, not the underlying root cause. It can support PROBABLE_ROOT_CAUSE with Runbook/RAG, but not ROOT_CAUSE_CONFIRMED by itself.",
                            "Fill the sufficiency rubric. ROOT_CAUSE_CONFIRMED requires rootCauseSupport and either multiSourceSupport or rootCauseSpecificEvidence. PROBABLE_ROOT_CAUSE requires sourceCoverage, negativeEvidenceConsidered, noContradiction, and collectableEvidenceGap=false."
                    ))
                    .metadata(Map.of("round", round, "maxRounds", maxRounds))
                    .build();
            output = opsMultiChatAgentService.reviewEvidence(input);
            if (output == null || !output.isSuccess()) {
                return null;
            }
            JSONObject json = OpsChatAgentJsonSupport.parseObject(output.getContent());
            String status = json.getString("status");
            if (isBlank(status)) {
                return null;
            }
            OpsEvidenceReviewResult result = OpsEvidenceReviewResult.builder()
                    .status(status)
                    .round(valueOrDefault(json.getInteger("round"), round))
                    .sufficient(valueOrDefault(json.getBoolean("sufficient"), fallbackResult.getSufficient()))
                    .confidenceScore(valueOrDefault(json.getInteger("confidenceScore"), fallbackResult.getConfidenceScore()))
                    .confirmedFacts(stringList(json.getJSONArray("confirmedFacts")))
                    .weakEvidence(stringList(json.getJSONArray("weakEvidence")))
                    .missingEvidence(stringList(json.getJSONArray("missingEvidence")))
                    .requiredTools(stringList(json.getJSONArray("requiredTools")))
                    .reportConstraints(stringList(json.getJSONArray("reportConstraints")))
                    .evidenceSemantics(parseEvidenceSemantics(json.getJSONArray("evidenceSemantics")))
                    .sufficiency(parseSufficiency(json.getJSONObject("sufficiency"), fallbackResult.getSufficiency()))
                    .conclusionType(blankToDefault(json.getString("conclusionType"), inferConclusionType(status, json.getString("rootCause"))))
                    .rootCause(json.getString("rootCause"))
                    .rootCauseCategory(json.getString("rootCauseCategory"))
                    .rootCauseConfidence(valueOrDefault(json.getInteger("rootCauseConfidence"),
                            valueOrDefault(json.getInteger("confidenceScore"), fallbackResult.getConfidenceScore())))
                    .rootCauseRationale(json.getString("rootCauseRationale"))
                    .candidateRootCauses(stringList(json.getJSONArray("candidateRootCauses")))
                    .rationale(blankToDefault(json.getString("rationale"), "Chat Agent reviewer result.")
                            + "; chatAgent=CHAT_AGENT"
                            + ", client=" + blankToDefault(output.getClientBeanName(), "unknown")
                            + ", resolution=" + blankToDefault(output.getResolutionSource(), "unknown")
                            + ", costMillis=" + valueOrDefault(output.getCostMillis(), 0L))
                    .build();
            return enforceSufficiency(result, fallbackResult, round, maxRounds);
        } catch (Exception ignore) {
            if (output != null && output.isSuccess()) {
                return normalizedChatAgentReview(fallbackResult, output,
                        "Evidence Reviewer Chat Agent returned content, but JSON normalization failed.");
            }
            return null;
        }
    }

    private OpsEvidenceReviewResult normalizedChatAgentReview(OpsEvidenceReviewResult fallbackResult,
                                                              OpsChatAgentOutput output,
                                                              String fallbackReason) {
        if (fallbackResult == null) {
            return null;
        }
        fallbackResult.setRationale(fallbackResult.getRationale()
                + "; chatAgent=CHAT_AGENT_NORMALIZED"
                + ", client=" + blankToDefault(output.getClientBeanName(), "unknown")
                + ", resolution=" + blankToDefault(output.getResolutionSource(), "unknown")
                + ", costMillis=" + valueOrDefault(output.getCostMillis(), 0L)
                + ", fallbackReason=" + fallbackReason);
        List<String> constraints = new ArrayList<>();
        if (fallbackResult.getReportConstraints() != null) {
            constraints.addAll(fallbackResult.getReportConstraints());
        }
        constraints.add("Evidence Reviewer Chat Agent was invoked, but its JSON was normalized by the execution framework.");
        fallbackResult.setReportConstraints(deduplicate(constraints));
        return fallbackResult;
    }

    private String buildToolConstraintsJson(int round, int maxRounds) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("supportedTools", List.of("query_prometheus", "query_elasticsearch", "query_skywalking_trace", "query_runbook"));
        constraints.put("round", round);
        constraints.put("maxRounds", Math.max(1, maxRounds));
        constraints.put("canRequestSupplementalEvidence", round < Math.max(1, maxRounds));
        constraints.put("governance", List.of(
                "requiredTools must be a subset of supportedTools.",
                "Request supplemental tools only for real collectable evidence gaps.",
                "NO_ANOMALY/OK from an already queried source is negative evidence, not a reason to query the same source again.",
                "Historical memory can guide interpretation but cannot prove the current root cause."
        ));
        return JSON.toJSONString(constraints);
    }

    private OpsEvidenceSufficiency buildBaselineSufficiency(IncidentCommandEntity command,
                                                            int availableSources,
                                                            boolean runbookAvailable,
                                                            List<String> missingEvidence,
                                                            List<String> weakEvidence) {
        boolean directEvidence = availableSources > 0;
        boolean multiSourceSupport = availableSources >= 2;
        boolean entityAlignment = command != null && !isBlank(command.getServiceName()) && directEvidence;
        List<String> missingCriticalEvidence = new ArrayList<>();
        if (missingEvidence != null) {
            missingCriticalEvidence.addAll(missingEvidence);
        }
        if (!multiSourceSupport) {
            missingCriticalEvidence.add("At least two independent telemetry sources are required before finalizing a root cause.");
        }
        if (!runbookAvailable) {
            missingCriticalEvidence.add("Runbook/RAG context is required before finalizing a root cause.");
        }
        return OpsEvidenceSufficiency.builder()
                .directEvidence(directEvidence)
                .multiSourceSupport(multiSourceSupport)
                .sourceCoverage(availableSources >= 2)
                .rootCauseSupport(false)
                .rootCauseSpecificEvidence(false)
                .negativeEvidenceConsidered(availableSources >= 2)
                .collectableEvidenceGap(missingEvidence != null && !missingEvidence.isEmpty())
                .temporalAlignment(true)
                .entityAlignment(entityAlignment)
                .runbookSupport(runbookAvailable)
                .noContradiction(true)
                .missingCriticalEvidence(deduplicate(missingCriticalEvidence))
                .contradictions(List.of())
                .rationale("Rule baseline only checks evidence-source coverage. It does not finalize root cause. weakEvidence=" + weakEvidence)
                .build();
    }

    private OpsEvidenceSufficiency parseSufficiency(JSONObject json, OpsEvidenceSufficiency fallback) {
        if (json == null) {
            return fallback;
        }
        return OpsEvidenceSufficiency.builder()
                .directEvidence(valueOrDefault(json.getBoolean("directEvidence"), bool(fallback == null ? null : fallback.getDirectEvidence())))
                .multiSourceSupport(valueOrDefault(json.getBoolean("multiSourceSupport"), bool(fallback == null ? null : fallback.getMultiSourceSupport())))
                .sourceCoverage(valueOrDefault(json.getBoolean("sourceCoverage"), bool(fallback == null ? null : fallback.getSourceCoverage())))
                .rootCauseSupport(valueOrDefault(json.getBoolean("rootCauseSupport"), bool(fallback == null ? null : fallback.getRootCauseSupport())))
                .rootCauseSpecificEvidence(valueOrDefault(json.getBoolean("rootCauseSpecificEvidence"), bool(fallback == null ? null : fallback.getRootCauseSpecificEvidence())))
                .negativeEvidenceConsidered(valueOrDefault(json.getBoolean("negativeEvidenceConsidered"), bool(fallback == null ? null : fallback.getNegativeEvidenceConsidered())))
                .collectableEvidenceGap(valueOrDefault(json.getBoolean("collectableEvidenceGap"), bool(fallback == null ? null : fallback.getCollectableEvidenceGap())))
                .temporalAlignment(valueOrDefault(json.getBoolean("temporalAlignment"), bool(fallback == null ? null : fallback.getTemporalAlignment())))
                .entityAlignment(valueOrDefault(json.getBoolean("entityAlignment"), bool(fallback == null ? null : fallback.getEntityAlignment())))
                .runbookSupport(valueOrDefault(json.getBoolean("runbookSupport"), bool(fallback == null ? null : fallback.getRunbookSupport())))
                .noContradiction(valueOrDefault(json.getBoolean("noContradiction"), bool(fallback == null ? null : fallback.getNoContradiction())))
                .missingCriticalEvidence(stringList(json.getJSONArray("missingCriticalEvidence")))
                .contradictions(stringList(json.getJSONArray("contradictions")))
                .rationale(json.getString("rationale"))
                .build();
    }

    private OpsEvidenceReviewResult enforceSufficiency(OpsEvidenceReviewResult result,
                                                       OpsEvidenceReviewResult fallbackResult,
                                                       int round,
                                                       int maxRounds) {
        if (result == null) {
            return null;
        }
        if (!"SUFFICIENT".equals(result.getStatus()) && !Boolean.TRUE.equals(result.getSufficient())) {
            return result;
        }
        List<String> violations = sufficiencyViolations(result);
        if (violations.isEmpty()) {
            return result;
        }
        List<String> requiredTools = result.getRequiredTools();
        if ((requiredTools == null || requiredTools.isEmpty()) && fallbackResult != null) {
            requiredTools = fallbackResult.getRequiredTools();
        }
        boolean canSupplement = round < maxRounds && requiredTools != null && !requiredTools.isEmpty();
        result.setStatus(canSupplement ? "NEED_MORE_EVIDENCE" : "INSUFFICIENT_FINAL");
        result.setSufficient(false);
        result.setRequiredTools(requiredTools == null ? List.of() : requiredTools);
        List<String> missing = new ArrayList<>();
        if (result.getMissingEvidence() != null) {
            missing.addAll(result.getMissingEvidence());
        }
        missing.addAll(violations);
        result.setMissingEvidence(deduplicate(missing));
        List<String> constraints = new ArrayList<>();
        if (result.getReportConstraints() != null) {
            constraints.addAll(result.getReportConstraints());
        }
        constraints.add("Evidence sufficiency rubric failed; do not finalize root cause.");
        result.setReportConstraints(deduplicate(constraints));
        result.setRationale(result.getRationale() + "; sufficiencyGate=DOWNGRADED, violations=" + violations);
        return result;
    }

    private List<String> sufficiencyViolations(OpsEvidenceReviewResult result) {
        List<String> violations = new ArrayList<>();
        OpsEvidenceSufficiency rubric = result.getSufficiency();
        if (rootCauseRequired(result) && isBlank(result.getRootCause())) {
            violations.add("Agent marked sufficient but did not provide rootCause.");
        }
        if (isBlank(result.getConclusionType())) {
            violations.add("Agent marked sufficient but did not provide conclusionType.");
        }
        if (rubric == null) {
            violations.add("Agent marked sufficient but did not provide sufficiency rubric.");
            return violations;
        }
        String conclusionType = result.getConclusionType();
        if (!Boolean.TRUE.equals(rubric.getDirectEvidence())) {
            violations.add("Sufficiency rubric failed: directEvidence=false.");
        }
        if (!Boolean.TRUE.equals(rubric.getTemporalAlignment())) {
            violations.add("Sufficiency rubric failed: temporalAlignment=false.");
        }
        if (!Boolean.TRUE.equals(rubric.getEntityAlignment())) {
            violations.add("Sufficiency rubric failed: entityAlignment=false.");
        }
        if (!Boolean.TRUE.equals(rubric.getRunbookSupport())) {
            violations.add("Sufficiency rubric failed: runbookSupport=false.");
        }
        if (!Boolean.TRUE.equals(rubric.getNoContradiction())) {
            violations.add("Sufficiency rubric failed: noContradiction=false.");
        }
        if (Boolean.TRUE.equals(rubric.getCollectableEvidenceGap())) {
            violations.add("Sufficiency rubric failed: collectableEvidenceGap=true.");
        }
        if ("ROOT_CAUSE_CONFIRMED".equals(conclusionType)) {
            boolean supportedByMultiSource = Boolean.TRUE.equals(rubric.getMultiSourceSupport());
            boolean supportedBySpecificSingleSource = Boolean.TRUE.equals(rubric.getRootCauseSpecificEvidence());
            if (!supportedByMultiSource && !supportedBySpecificSingleSource) {
                violations.add("Confirmed root cause requires either multiSourceSupport=true or rootCauseSpecificEvidence=true.");
            }
            if (!Boolean.TRUE.equals(rubric.getRootCauseSupport())) {
                violations.add("Confirmed root cause requires rootCauseSupport=true.");
            }
            if (rubric.getMissingCriticalEvidence() != null && !rubric.getMissingCriticalEvidence().isEmpty()) {
                violations.addAll(rubric.getMissingCriticalEvidence());
            }
        } else if ("PROBABLE_ROOT_CAUSE".equals(conclusionType)) {
            if (isBlank(result.getRootCause())) {
                violations.add("Probable root cause requires a useful rootCause candidate.");
            }
            if (!Boolean.TRUE.equals(rubric.getSourceCoverage())) {
                violations.add("Probable root cause requires sourceCoverage=true.");
            }
            if (!Boolean.TRUE.equals(rubric.getNegativeEvidenceConsidered())) {
                violations.add("Probable root cause requires negativeEvidenceConsidered=true.");
            }
        } else if ("INVESTIGATION_COMPLETE_ROOT_CAUSE_UNRESOLVED".equals(conclusionType)) {
            if (!Boolean.TRUE.equals(rubric.getSourceCoverage())) {
                violations.add("Unresolved final conclusion requires sourceCoverage=true.");
            }
        } else {
            violations.add("Unsupported conclusionType for SUFFICIENT result: " + conclusionType);
        }
        if (rubric.getContradictions() != null && !rubric.getContradictions().isEmpty()) {
            violations.addAll(rubric.getContradictions());
        }
        return deduplicate(violations);
    }

    private boolean rootCauseRequired(OpsEvidenceReviewResult result) {
        return result != null
                && ("ROOT_CAUSE_CONFIRMED".equals(result.getConclusionType())
                || "PROBABLE_ROOT_CAUSE".equals(result.getConclusionType()));
    }

    private String inferConclusionType(String status, String rootCause) {
        if (!"SUFFICIENT".equals(status)) {
            return "NEED_MORE_EVIDENCE".equals(status) ? "NEED_MORE_EVIDENCE" : "";
        }
        return isBlank(rootCause) ? "INVESTIGATION_COMPLETE_ROOT_CAUSE_UNRESOLVED" : "PROBABLE_ROOT_CAUSE";
    }

    private String buildEvidenceJson(DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (context == null) {
            return JSON.toJSONString(evidence);
        }
        evidence.put("metricEvidence", context.getMetricEvidence());
        evidence.put("logEvidence", context.getLogEvidence());
        evidence.put("traceEvidence", context.getTraceEvidence());
        evidence.put("evidenceSignals", context.getEvidenceSignals());
        evidence.put("evidenceSemantics", context.getEvidenceSemantics());
        evidence.put("rootCauseCandidates", context.getRootCauseCandidates());
        evidence.put("runbookMatches", context.getRunbookMatches());
        return JSON.toJSONString(evidence);
    }

    private List<EvidenceSemanticEntity> parseEvidenceSemantics(JSONArray array) {
        List<EvidenceSemanticEntity> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (Object item : array) {
            if (!(item instanceof JSONObject json)) {
                continue;
            }
            values.add(EvidenceSemanticEntity.builder()
                    .signalId(json.getString("signalId"))
                    .source(json.getString("source"))
                    .signalName(json.getString("signalName"))
                    .semanticType(json.getString("semanticType"))
                    .causalLevel(json.getString("causalLevel"))
                    .specificity(json.getString("specificity"))
                    .evidenceStrength(valueOrDefault(json.getInteger("evidenceStrength"), 0))
                    .supports(stringList(json.getJSONArray("supports")))
                    .weakens(stringList(json.getJSONArray("weakens")))
                    .reasoning(json.getString("reasoning"))
                    .build());
        }
        return values;
    }

    private void addSourceReview(String sourceName,
                                 boolean available,
                                 String summary,
                                 String toolName,
                                 List<String> confirmedFacts,
                                 List<String> missingEvidence,
                                 Set<String> requiredTools) {
        if (available) {
            confirmedFacts.add(sourceName + " available: " + blankToDefault(summary, "summary is empty"));
            return;
        }
        missingEvidence.add(sourceName + " is unavailable or was skipped, so it should be collected before finalizing a high-confidence conclusion.");
        requiredTools.add(toolName);
    }

    private List<String> buildReportConstraints(String status, IncidentCommandEntity command) {
        List<String> constraints = new ArrayList<>();
        constraints.add("Only write confirmed facts that are supported by Prometheus, ELK, SkyWalking, or the structured evidence chain.");
        constraints.add("Do not treat user description as confirmed evidence. service=" + blankToDefault(command.getServiceName(), "unknown"));
        constraints.add("Runbook can only support remediation suggestions, not incident facts.");
        if (!"SUFFICIENT".equals(status)) {
            constraints.add("Because evidence is insufficient, final root cause must be written as a hypothesis with missing evidence listed.");
        }
        return constraints;
    }

    private String buildRationale(String status, int availableSources, int topConfidence, int round, int maxRounds) {
        return "status=" + status
                + ", availableSources=" + availableSources
                + ", topConfidence=" + topConfidence
                + ", threshold=" + Math.round(minConfidence * 100)
                + ", round=" + round + "/" + maxRounds;
    }

    private RootCauseCandidateEntity topCandidate(List<RootCauseCandidateEntity> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .max(Comparator.comparing(candidate -> candidate.getConfidence() == null ? 0 : candidate.getConfidence()))
                .orElse(null);
    }

    private int availableSources(MetricEvidenceEntity metricEvidence,
                                 LogEvidenceEntity logEvidence,
                                 TraceEvidenceEntity traceEvidence) {
        int count = 0;
        if (available(metricEvidence)) count++;
        if (available(logEvidence)) count++;
        if (available(traceEvidence)) count++;
        return count;
    }

    private boolean available(MetricEvidenceEntity evidence) {
        return evidence != null && evidence.isAvailable();
    }

    private boolean available(LogEvidenceEntity evidence) {
        return evidence != null && evidence.isAvailable();
    }

    private boolean available(TraceEvidenceEntity evidence) {
        return evidence != null && evidence.isAvailable();
    }

    private String summary(MetricEvidenceEntity evidence) {
        return evidence == null ? "" : evidence.getSummary();
    }

    private String summary(LogEvidenceEntity evidence) {
        return evidence == null ? "" : evidence.getSummary();
    }

    private String summary(TraceEvidenceEntity evidence) {
        return evidence == null ? "" : evidence.getSummary();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private List<String> stringList(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (Object item : array) {
            if (item == null) {
                continue;
            }
            String value = String.valueOf(item).trim();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Boolean bool(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private List<String> deduplicate(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (!isBlank(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

