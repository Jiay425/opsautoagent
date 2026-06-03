package com.opsautoagent.domain.codeops.agent.skill;

import com.alibaba.fastjson.JSON;
import com.opsautoagent.domain.codeops.agent.evidence.IncidentEvidenceExtractor;
import com.opsautoagent.domain.codeops.agent.fixture.IncidentFixtureEvidence;
import com.opsautoagent.domain.codeops.agent.fixture.IncidentFixtureEvidenceService;
import com.opsautoagent.domain.codeops.model.entity.OpsDiagnosisSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsLogGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsMetricGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsRunbookGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsTraceGateway;
import com.opsautoagent.domain.ops.adapter.repository.IOpsIncidentRepository;
import com.opsautoagent.domain.ops.agent.evidence.OpsEvidenceSignalExtractor;
import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;
import com.opsautoagent.domain.ops.model.entity.EvidenceSignalEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.LogEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.MetricEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import com.opsautoagent.domain.ops.model.entity.TraceEvidenceEntity;
import com.opsautoagent.domain.ops.service.OpsIncidentExecuteStrategy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OpsDiagnosisEngineeringSkill implements EngineeringSkill {

    public static final String SKILL_ID = "ops_diagnosis";

    private static final Pattern SERVICE_PATTERN = Pattern.compile("([a-zA-Z0-9_-]+(?:-service|-app|-gateway))");

    private final OpsIncidentExecuteStrategy opsIncidentExecuteStrategy;

    private final IOpsIncidentRepository opsIncidentRepository;

    private final IncidentFixtureEvidenceService fixtureEvidenceService;

    private final IncidentEvidenceExtractor evidenceExtractor;

    private final IOpsMetricGateway metricGateway;

    private final IOpsLogGateway logGateway;

    private final IOpsTraceGateway traceGateway;

    private final IOpsRunbookGateway runbookGateway;

    private final OpsEvidenceSignalExtractor evidenceSignalExtractor;

    public OpsDiagnosisEngineeringSkill(OpsIncidentExecuteStrategy opsIncidentExecuteStrategy,
                                        IOpsIncidentRepository opsIncidentRepository,
                                        IncidentFixtureEvidenceService fixtureEvidenceService,
                                        IncidentEvidenceExtractor evidenceExtractor,
                                        IOpsMetricGateway metricGateway,
                                        IOpsLogGateway logGateway,
                                        IOpsTraceGateway traceGateway,
                                        IOpsRunbookGateway runbookGateway,
                                        OpsEvidenceSignalExtractor evidenceSignalExtractor) {
        this.opsIncidentExecuteStrategy = opsIncidentExecuteStrategy;
        this.opsIncidentRepository = opsIncidentRepository;
        this.fixtureEvidenceService = fixtureEvidenceService;
        this.evidenceExtractor = evidenceExtractor;
        this.metricGateway = metricGateway;
        this.logGateway = logGateway;
        this.traceGateway = traceGateway;
        this.runbookGateway = runbookGateway;
        this.evidenceSignalExtractor = evidenceSignalExtractor;
    }

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("Ops Diagnosis Skill")
                .description("Reuse AutoAgent diagnosis capability to collect metric, log, trace and runbook evidence.")
                .supportedTaskTypes(List.of("INCIDENT_TO_FIX"))
                .requiredTools(List.of("ops.query_prometheus", "ops.search_logs", "ops.query_trace", "knowledge.search"))
                .riskLevel("READ_ONLY")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        IncidentCommandEntity command = buildCommand(task);
        OpsDiagnosisSkillResultEntity skillResult;
        String fixtureCase = task.getContext() == null ? null : stringValue(task.getContext().get("fixtureCase"));
        String evidenceMode = task.getContext() == null ? "" : stringValue(task.getContext().get("evidenceMode"));
        if (!isBlank(fixtureCase) && "FIXTURE".equalsIgnoreCase(value(evidenceMode))) {
            skillResult = toFixtureSkillResult(command, fixtureEvidenceService.load(fixtureCase));
        } else if (hasAlertmanagerEvidence(task)) {
            skillResult = toLiveAlertEvidenceSkillResult(command, task);
            if (!hasExternalEvidence(skillResult) && !isBlank(fixtureCase) && isFixtureFallbackAllowed(task)) {
                skillResult = toFixtureSkillResult(command, fixtureEvidenceService.load(fixtureCase));
                skillResult.setStatus("FIXTURE_FALLBACK_READY");
                skillResult.getEvidenceDetails().put("fallbackReason",
                        "Live telemetry gateways returned no usable external evidence and fixture fallback was explicitly allowed.");
            }
        } else if (!isBlank(fixtureCase)) {
            skillResult = toFixtureSkillResult(command, fixtureEvidenceService.load(fixtureCase));
        } else {
            try {
                opsIncidentExecuteStrategy.execute(command, null);
                DiagnosisRecordEntity record = opsIncidentRepository.queryDiagnosisRecord(command.getDiagnosisId());
                skillResult = toSkillResult(command, record, null);
            } catch (Exception e) {
                skillResult = toSkillResult(command, null, e);
            }
        }
        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status(skillResult.getErrorMessage() == null ? "SUCCESS" : "FAILED")
                .summary(buildSummary(skillResult))
                .evidence(buildEvidence(skillResult))
                .nextActions(List.of("Planner Agent 将证据线索传给 Code Repair Agent", "Code Repair Agent 基于 stacktrace/Trace/源码定位并修复", "Reviewer Agent 审查修复是否消除 5xx"))
                .rawOutput(buildRawOutput(command, skillResult))
                .build();
    }

    private IncidentCommandEntity buildCommand(EngineeringTaskEntity task) {
        Map<String, Object> context = task.getContext() == null ? Map.of() : task.getContext();
        String serviceName = firstNonBlank(
                stringValue(context.get("serviceName")),
                stringValue(context.get("service")),
                extractServiceName(task.getGoal()),
                "unknown-service");
        String traceId = firstNonBlank(stringValue(context.get("traceId")), stringValue(context.get("trace")));
        String startTime = firstNonBlank(stringValue(context.get("startTime")), defaultStartTime());
        String endTime = firstNonBlank(stringValue(context.get("endTime")), defaultEndTime());
        return IncidentCommandEntity.builder()
                .serviceName(serviceName)
                .startTime(startTime)
                .endTime(endTime)
                .problem(task.getGoal())
                .traceId(traceId)
                .maxStep(6)
                .sessionId("codeops-" + UUID.randomUUID())
                .diagnosisId(firstNonBlank(stringValue(context.get("opsDiagnosisId")), "codeops-diagnosis-" + UUID.randomUUID()))
                .build();
    }

    private OpsDiagnosisSkillResultEntity toSkillResult(IncidentCommandEntity command,
                                                        DiagnosisRecordEntity record,
                                                        Exception error) {
        if (error != null) {
            return OpsDiagnosisSkillResultEntity.builder()
                    .diagnosisId(command.getDiagnosisId())
                    .sessionId(command.getSessionId())
                    .serviceName(command.getServiceName())
                    .timeWindow(command.getStartTime() + " ~ " + command.getEndTime())
                    .traceId(command.getTraceId())
                    .status("FAILED")
                    .reportSummary("")
                    .codeHints(List.of())
                    .evidenceSources(List.of())
                    .evidenceDetails(Map.of())
                    .evidenceCoverage(Map.of("mode", "ERROR", "realEvidenceCoverage", 0D))
                    .evidenceProvenance(List.of())
                    .errorMessage(error.getMessage())
                    .build();
        }
        List<String> evidenceSources = new ArrayList<>();
        if (!isBlank(record == null ? null : record.getMetricEvidenceJson())) {
            evidenceSources.add("Prometheus metrics");
        }
        if (!isBlank(record == null ? null : record.getLogEvidenceJson())) {
            evidenceSources.add("Elasticsearch logs");
        }
        if (!isBlank(record == null ? null : record.getTraceEvidenceJson())) {
            evidenceSources.add("SkyWalking traces");
        }
        if (!isBlank(record == null ? null : record.getRunbookJson())) {
            evidenceSources.add("Runbook RAG");
        }
        String allEvidenceText = String.join("\n",
                value(record == null ? null : record.getLogEvidenceJson()),
                value(record == null ? null : record.getTraceEvidenceJson()),
                value(record == null ? null : record.getEvidenceChainJson()),
                value(record == null ? null : record.getReport()));
        return OpsDiagnosisSkillResultEntity.builder()
                .diagnosisId(command.getDiagnosisId())
                .sessionId(command.getSessionId())
                .serviceName(command.getServiceName())
                .timeWindow(command.getStartTime() + " ~ " + command.getEndTime())
                .traceId(command.getTraceId())
                .status(record == null ? "NO_RECORD" : record.getStatus())
                .reportSummary(abbreviate(record == null ? "" : record.getReport(), 1200))
                .codeHints(evidenceExtractor.extractCodeHints(allEvidenceText))
                .evidenceSources(evidenceSources)
                .evidenceDetails(Map.of(
                        "metricEvidence", value(record == null ? null : record.getMetricEvidenceJson()),
                        "logEvidence", value(record == null ? null : record.getLogEvidenceJson()),
                        "traceEvidence", value(record == null ? null : record.getTraceEvidenceJson()),
                        "runbook", value(record == null ? null : record.getRunbookJson())
                ))
                .evidenceCoverage(Map.of(
                        "mode", "LEGACY_DIAGNOSIS_RECORD",
                        "realEvidenceCoverage", legacyCoverage(evidenceSources),
                        "realAvailableSources", evidenceSources.size(),
                        "fixtureFallbackUsed", false))
                .evidenceProvenance(List.of())
                .build();
    }

    private OpsDiagnosisSkillResultEntity toLiveAlertEvidenceSkillResult(IncidentCommandEntity command,
                                                                         EngineeringTaskEntity task) {
        Map<String, Object> context = task.getContext() == null ? Map.of() : task.getContext();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("alertmanagerPayload", context.get("alertmanagerPayload"));
        details.put("alertLabels", context.get("alertLabels") == null ? Map.of() : context.get("alertLabels"));
        details.put("alertAnnotations", context.get("alertAnnotations") == null ? Map.of() : context.get("alertAnnotations"));
        details.put("source", context.get("source"));
        details.put("evidenceMode", firstNonBlank(stringValue(context.get("evidenceMode")), "LIVE"));
        details.put("fixtureFallbackAllowed", context.getOrDefault("fixtureFallbackAllowed", false));

        MetricEvidenceEntity metricEvidence = safeQueryMetrics(command);
        LogEvidenceEntity logEvidence = safeQueryLogs(command);
        TraceEvidenceEntity traceEvidence = safeQueryTrace(command);
        List<EvidenceSignalEntity> signals = evidenceSignalExtractor.extract(command, metricEvidence, logEvidence, traceEvidence);
        List<RunbookMatchEntity> runbookMatches = safeSearchRunbooks(command, signals, details);

        details.put("prometheus", evidenceEnvelope("Prometheus", metricEvidence));
        details.put("elasticsearch", evidenceEnvelope("Elasticsearch", logEvidence));
        details.put("skywalking", evidenceEnvelope("SkyWalking", traceEvidence));
        details.put("evidenceSignals", signals);
        details.put("runbookMatches", runbookMatches == null ? List.of() : runbookMatches);
        details.put("telemetryReadiness", telemetryReadiness(metricEvidence, logEvidence, traceEvidence, runbookMatches));
        details.put("evidenceProvenance", liveEvidenceProvenance(metricEvidence, logEvidence, traceEvidence, runbookMatches));
        details.put("evidenceCoverage", liveEvidenceCoverage(metricEvidence, logEvidence, traceEvidence, runbookMatches));

        String evidenceText = String.join("\n",
                JSON.toJSONString(details),
                task.getGoal(),
                summary(metricEvidence),
                summary(logEvidence),
                summary(traceEvidence),
                JSON.toJSONString(runbookMatches));
        return OpsDiagnosisSkillResultEntity.builder()
                .diagnosisId(command.getDiagnosisId())
                .sessionId(command.getSessionId())
                .serviceName(command.getServiceName())
                .timeWindow(command.getStartTime() + " ~ " + command.getEndTime())
                .traceId(command.getTraceId())
                .status("LIVE_EVIDENCE_READY")
                .reportSummary(abbreviate(evidenceText, 1200))
                .codeHints(evidenceExtractor.extractCodeHints(evidenceText))
                .evidenceSources(liveEvidenceSources(metricEvidence, logEvidence, traceEvidence, runbookMatches))
                .evidenceDetails(details)
                .evidenceCoverage(liveEvidenceCoverage(metricEvidence, logEvidence, traceEvidence, runbookMatches))
                .evidenceProvenance(liveEvidenceProvenance(metricEvidence, logEvidence, traceEvidence, runbookMatches))
                .build();
    }

    private MetricEvidenceEntity safeQueryMetrics(IncidentCommandEntity command) {
        try {
            MetricEvidenceEntity evidence = metricGateway.queryMetrics(command);
            return evidence == null ? unavailableMetric("Prometheus returned null evidence.") : evidence;
        } catch (Exception e) {
            return unavailableMetric("Prometheus query failed: " + e.getMessage());
        }
    }

    private LogEvidenceEntity safeQueryLogs(IncidentCommandEntity command) {
        try {
            LogEvidenceEntity evidence = logGateway.queryLogs(command);
            return evidence == null ? unavailableLog("Elasticsearch returned null evidence.") : evidence;
        } catch (Exception e) {
            return unavailableLog("Elasticsearch query failed: " + e.getMessage());
        }
    }

    private TraceEvidenceEntity safeQueryTrace(IncidentCommandEntity command) {
        try {
            TraceEvidenceEntity evidence = traceGateway.queryTrace(command);
            return evidence == null ? unavailableTrace("SkyWalking returned null evidence.") : evidence;
        } catch (Exception e) {
            return unavailableTrace("SkyWalking query failed: " + e.getMessage());
        }
    }

    private List<RunbookMatchEntity> safeSearchRunbooks(IncidentCommandEntity command,
                                                        List<EvidenceSignalEntity> signals,
                                                        Map<String, Object> details) {
        try {
            return runbookGateway.searchByEvidenceSignals(command, signals == null ? List.of() : signals, 5);
        } catch (Exception e) {
            details.put("runbookError", e.getMessage());
            return List.of();
        }
    }

    private MetricEvidenceEntity unavailableMetric(String message) {
        return MetricEvidenceEntity.builder()
                .source("Prometheus")
                .available(false)
                .summary(message)
                .observations(List.of("UNAVAILABLE: " + message))
                .rawData("")
                .sourceMetadata(Map.of(
                        "sourceType", "PROMETHEUS",
                        "sourceMode", "UNAVAILABLE",
                        "error", value(message),
                        "fixtureFallback", false))
                .build();
    }

    private LogEvidenceEntity unavailableLog(String message) {
        return LogEvidenceEntity.builder()
                .source("Elasticsearch")
                .available(false)
                .summary(message)
                .errorSamples(List.of("UNAVAILABLE: " + message))
                .rawData("")
                .sourceMetadata(Map.of(
                        "sourceType", "ELASTICSEARCH",
                        "sourceMode", "UNAVAILABLE",
                        "error", value(message),
                        "fixtureFallback", false))
                .build();
    }

    private TraceEvidenceEntity unavailableTrace(String message) {
        return TraceEvidenceEntity.builder()
                .source("SkyWalking")
                .available(false)
                .summary(message)
                .spans(List.of("UNAVAILABLE: " + message))
                .rawData("")
                .sourceMetadata(Map.of(
                        "sourceType", "SKYWALKING",
                        "sourceMode", "UNAVAILABLE",
                        "error", value(message),
                        "fixtureFallback", false))
                .build();
    }

    private Map<String, Object> evidenceEnvelope(String source, MetricEvidenceEntity evidence) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("source", source);
        envelope.put("available", evidence != null && evidence.isAvailable());
        envelope.put("summary", summary(evidence));
        envelope.put("observations", evidence == null || evidence.getObservations() == null ? List.of() : evidence.getObservations());
        envelope.put("sourceMetadata", evidence == null || evidence.getSourceMetadata() == null ? Map.of() : evidence.getSourceMetadata());
        return envelope;
    }

    private Map<String, Object> evidenceEnvelope(String source, LogEvidenceEntity evidence) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("source", source);
        envelope.put("available", evidence != null && evidence.isAvailable());
        envelope.put("summary", summary(evidence));
        envelope.put("samples", evidence == null || evidence.getErrorSamples() == null ? List.of() : evidence.getErrorSamples());
        envelope.put("sourceMetadata", evidence == null || evidence.getSourceMetadata() == null ? Map.of() : evidence.getSourceMetadata());
        return envelope;
    }

    private Map<String, Object> evidenceEnvelope(String source, TraceEvidenceEntity evidence) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("source", source);
        envelope.put("available", evidence != null && evidence.isAvailable());
        envelope.put("summary", summary(evidence));
        envelope.put("spans", evidence == null || evidence.getSpans() == null ? List.of() : evidence.getSpans());
        envelope.put("sourceMetadata", evidence == null || evidence.getSourceMetadata() == null ? Map.of() : evidence.getSourceMetadata());
        return envelope;
    }

    private Map<String, Object> telemetryReadiness(MetricEvidenceEntity metricEvidence,
                                                   LogEvidenceEntity logEvidence,
                                                   TraceEvidenceEntity traceEvidence,
                                                   List<RunbookMatchEntity> runbookMatches) {
        return Map.of(
                "prometheusAvailable", metricEvidence != null && metricEvidence.isAvailable(),
                "elasticsearchAvailable", logEvidence != null && logEvidence.isAvailable(),
                "skywalkingAvailable", traceEvidence != null && traceEvidence.isAvailable(),
                "runbookMatched", runbookMatches != null && !runbookMatches.isEmpty(),
                "allTelemetryAvailable", metricEvidence != null && metricEvidence.isAvailable()
                        && logEvidence != null && logEvidence.isAvailable()
                        && traceEvidence != null && traceEvidence.isAvailable());
    }

    private Map<String, Object> liveEvidenceCoverage(MetricEvidenceEntity metricEvidence,
                                                     LogEvidenceEntity logEvidence,
                                                     TraceEvidenceEntity traceEvidence,
                                                     List<RunbookMatchEntity> runbookMatches) {
        int realAvailable = 0;
        if (metricEvidence != null && metricEvidence.isAvailable()) realAvailable++;
        if (logEvidence != null && logEvidence.isAvailable()) realAvailable++;
        if (traceEvidence != null && traceEvidence.isAvailable()) realAvailable++;
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("mode", "LIVE_GATEWAY");
        coverage.put("externalSourceCount", 3);
        coverage.put("realAvailableSources", realAvailable);
        coverage.put("realEvidenceCoverage", realAvailable / 3D);
        coverage.put("fixtureFallbackUsed", false);
        coverage.put("prometheusAvailable", metricEvidence != null && metricEvidence.isAvailable());
        coverage.put("elasticsearchAvailable", logEvidence != null && logEvidence.isAvailable());
        coverage.put("skywalkingAvailable", traceEvidence != null && traceEvidence.isAvailable());
        coverage.put("runbookChunkHits", runbookMatches == null ? 0 : runbookMatches.size());
        return coverage;
    }

    private List<Map<String, Object>> liveEvidenceProvenance(MetricEvidenceEntity metricEvidence,
                                                             LogEvidenceEntity logEvidence,
                                                             TraceEvidenceEntity traceEvidence,
                                                             List<RunbookMatchEntity> runbookMatches) {
        List<Map<String, Object>> provenance = new ArrayList<>();
        provenance.add(provenanceEntry("Prometheus", metricEvidence == null ? null : metricEvidence.getSourceMetadata(),
                metricEvidence != null && metricEvidence.isAvailable()));
        provenance.add(provenanceEntry("Elasticsearch", logEvidence == null ? null : logEvidence.getSourceMetadata(),
                logEvidence != null && logEvidence.isAvailable()));
        provenance.add(provenanceEntry("SkyWalking", traceEvidence == null ? null : traceEvidence.getSourceMetadata(),
                traceEvidence != null && traceEvidence.isAvailable()));
        if (runbookMatches != null) {
            for (RunbookMatchEntity match : runbookMatches) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", "Runbook RAG");
                item.put("sourceType", "RUNBOOK_RAG");
                item.put("sourceMode", "RAG_CHUNK_RETRIEVAL");
                item.put("available", true);
                item.put("runbookId", value(match.getRunbookId()));
                item.put("chunkId", value(match.getChunkId()));
                item.put("chunkIndex", match.getChunkIndex());
                item.put("rank", match.getRank());
                item.put("retrievalMode", value(match.getRetrievalMode()));
                item.put("hybridScore", match.getHybridScore());
                item.put("rankExplanation", value(match.getRankExplanation()));
                provenance.add(item);
            }
        }
        return provenance;
    }

    private Map<String, Object> provenanceEntry(String source, Map<String, Object> metadata, boolean available) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("source", source);
        entry.put("available", available);
        if (metadata != null) {
            entry.putAll(metadata);
        }
        return entry;
    }

    private double legacyCoverage(List<String> evidenceSources) {
        if (evidenceSources == null || evidenceSources.isEmpty()) {
            return 0D;
        }
        long external = evidenceSources.stream()
                .filter(source -> source.contains("Prometheus") || source.contains("Elasticsearch") || source.contains("SkyWalking"))
                .count();
        return external / 3D;
    }

    private List<String> liveEvidenceSources(MetricEvidenceEntity metricEvidence,
                                             LogEvidenceEntity logEvidence,
                                             TraceEvidenceEntity traceEvidence,
                                             List<RunbookMatchEntity> runbookMatches) {
        List<String> sources = new ArrayList<>();
        sources.add("Alertmanager webhook payload (live)");
        sources.add("Prometheus metrics (live:" + availability(metricEvidence) + ")");
        sources.add("Elasticsearch logs (live:" + availability(logEvidence) + ")");
        sources.add("SkyWalking traces (live:" + availability(traceEvidence) + ")");
        sources.add("Runbook RAG (matches=" + (runbookMatches == null ? 0 : runbookMatches.size()) + ")");
        return sources;
    }

    private String availability(MetricEvidenceEntity evidence) {
        return evidence != null && evidence.isAvailable() ? "available" : "unavailable";
    }

    private String availability(LogEvidenceEntity evidence) {
        return evidence != null && evidence.isAvailable() ? "available" : "unavailable";
    }

    private String availability(TraceEvidenceEntity evidence) {
        return evidence != null && evidence.isAvailable() ? "available" : "unavailable";
    }

    private String summary(MetricEvidenceEntity evidence) {
        return evidence == null ? "" : value(evidence.getSummary());
    }

    private String summary(LogEvidenceEntity evidence) {
        return evidence == null ? "" : value(evidence.getSummary());
    }

    private String summary(TraceEvidenceEntity evidence) {
        return evidence == null ? "" : value(evidence.getSummary());
    }

    private OpsDiagnosisSkillResultEntity toFixtureSkillResult(IncidentCommandEntity command,
                                                               IncidentFixtureEvidence fixtureEvidence) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fixtureCaseId", fixtureEvidence.getCaseId());
        details.put("fixtureBasePath", fixtureEvidence.getBasePath());
        details.put("alert", fixtureEvidence.getAlert() == null ? Map.of() : fixtureEvidence.getAlert());
        details.put("prometheus", fixtureEvidence.getPrometheus() == null ? Map.of() : fixtureEvidence.getPrometheus());
        details.put("logs", fixtureEvidence.getLogs() == null ? Map.of() : fixtureEvidence.getLogs());
        details.put("trace", fixtureEvidence.getTrace() == null ? Map.of() : fixtureEvidence.getTrace());
        return OpsDiagnosisSkillResultEntity.builder()
                .diagnosisId(command.getDiagnosisId())
                .sessionId(command.getSessionId())
                .serviceName(command.getServiceName())
                .timeWindow(command.getStartTime() + " ~ " + command.getEndTime())
                .traceId(command.getTraceId())
                .status(fixtureEvidence.isAvailable() ? "FIXTURE_READY" : "FIXTURE_FAILED")
                .reportSummary(abbreviate(fixtureEvidence.getReportSummary(), 1200))
                .codeHints(fixtureEvidence.getCodeHints() == null ? List.of() : fixtureEvidence.getCodeHints())
                .evidenceSources(fixtureEvidence.getEvidenceSources() == null ? List.of() : fixtureEvidence.getEvidenceSources())
                .evidenceDetails(details)
                .evidenceCoverage(Map.of(
                        "mode", "FIXTURE",
                        "realEvidenceCoverage", 0D,
                        "realAvailableSources", 0,
                        "fixtureFallbackUsed", true,
                        "fixtureSources", fixtureEvidence.getEvidenceSources() == null ? 0 : fixtureEvidence.getEvidenceSources().size()))
                .evidenceProvenance(List.of(Map.of(
                        "sourceType", "FIXTURE",
                        "sourceMode", "FIXTURE_FALLBACK",
                        "caseId", value(fixtureEvidence.getCaseId()),
                        "basePath", value(fixtureEvidence.getBasePath()))))
                .errorMessage(fixtureEvidence.getErrorMessage())
                .build();
    }

    private Map<String, Object> buildRawOutput(IncidentCommandEntity command, OpsDiagnosisSkillResultEntity skillResult) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("phase", "PHASE_4_OPS_DIAGNOSIS_SKILL");
        output.put("command", command);
        output.put("opsDiagnosis", skillResult);
        output.put("evidenceDetails", skillResult.getEvidenceDetails() == null ? Map.of() : skillResult.getEvidenceDetails());
        output.put("evidenceCoverage", skillResult.getEvidenceCoverage() == null ? Map.of() : skillResult.getEvidenceCoverage());
        output.put("evidenceProvenance", skillResult.getEvidenceProvenance() == null ? List.of() : skillResult.getEvidenceProvenance());
        return output;
    }

    private List<String> buildEvidence(OpsDiagnosisSkillResultEntity result) {
        List<String> evidence = new ArrayList<>();
        evidence.add("服务：" + result.getServiceName());
        evidence.add("时间窗口：" + result.getTimeWindow());
        evidence.add("诊断 ID：" + result.getDiagnosisId());
        evidence.add("证据来源：" + (result.getEvidenceSources() == null || result.getEvidenceSources().isEmpty() ? "无" : String.join(", ", result.getEvidenceSources())));
        evidence.add("代码定位线索：" + (result.getCodeHints() == null || result.getCodeHints().isEmpty() ? "无" : String.join(", ", result.getCodeHints())));
        if (!isBlank(result.getReportSummary())) {
            evidence.add("诊断报告摘要：" + result.getReportSummary());
        }
        if (!isBlank(result.getErrorMessage())) {
            evidence.add("诊断错误：" + result.getErrorMessage());
        }
        return evidence;
    }

    private String buildSummary(OpsDiagnosisSkillResultEntity result) {
        if (!isBlank(result.getErrorMessage())) {
            return "OpsDiagnosisSkill 执行失败：service=" + result.getServiceName()
                    + "，diagnosisId=" + result.getDiagnosisId()
                    + "，error=" + result.getErrorMessage();
        }
        return "OpsDiagnosisSkill 已完成：service=" + result.getServiceName()
                + "，diagnosisId=" + result.getDiagnosisId()
                + "，evidenceSources=" + (result.getEvidenceSources() == null ? 0 : result.getEvidenceSources().size())
                + "，realEvidenceCoverage=" + (result.getEvidenceCoverage() == null ? "N/A" : result.getEvidenceCoverage().get("realEvidenceCoverage"))
                + "，codeHints=" + (result.getCodeHints() == null ? 0 : result.getCodeHints().size());
    }

    private String extractServiceName(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String defaultStartTime() {
        return LocalDateTime.now().minusMinutes(30).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String defaultEndTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String ? (String) value : JSON.toJSONString(value);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasAlertmanagerEvidence(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return false;
        }
        return !isBlank(stringValue(task.getContext().get("alertmanagerPayload")));
    }

    private boolean isFixtureFallbackAllowed(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return false;
        }
        Object value = task.getContext().get("fixtureFallbackAllowed");
        return value instanceof Boolean bool ? bool : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean hasExternalEvidence(OpsDiagnosisSkillResultEntity result) {
        if (result == null || result.getEvidenceCoverage() == null) {
            return false;
        }
        Object count = result.getEvidenceCoverage().get("realAvailableSources");
        if (count instanceof Number number) {
            return number.intValue() > 0;
        }
        return false;
    }

}
