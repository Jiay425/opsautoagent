package com.opsautoagent.domain.ops.service.execute;

import com.opsautoagent.domain.ops.adapter.gateway.IOpsLogGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsMetricGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsRunbookGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsTraceGateway;
import com.opsautoagent.domain.ops.adapter.repository.IOpsGovernanceRepository;
import com.opsautoagent.domain.ops.adapter.repository.IOpsIncidentRepository;
import com.opsautoagent.domain.ops.agent.governance.OpsToolGovernanceDecision;
import com.opsautoagent.domain.ops.agent.governance.OpsToolGovernanceService;
import com.opsautoagent.domain.ops.agent.governance.OpsToolProtocolResolver;
import com.opsautoagent.domain.ops.agent.memory.OpsIncidentWorkingMemoryService;
import com.opsautoagent.domain.ops.agent.memory.OpsHistoricalIncidentMemoryService;
import com.opsautoagent.domain.ops.agent.plan.OpsAgentPlanExecutionService;
import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;
import com.opsautoagent.domain.ops.model.entity.EvidenceItemEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.LogEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.MetricEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAnalyzeEventEntity;
import com.opsautoagent.domain.ops.model.entity.OpsToolCallLogEntity;
import com.opsautoagent.domain.ops.model.entity.RootCauseCandidateEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import com.opsautoagent.domain.ops.model.entity.TraceEvidenceEntity;
import com.opsautoagent.domain.ops.service.OpsSensitiveDataMasker;
import com.opsautoagent.domain.common.tree.AbstractMultiThreadStrategyRouter;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
public abstract class AbstractOpsAgentExecuteSupport extends AbstractMultiThreadStrategyRouter<IncidentCommandEntity, DefaultOpsAgentExecuteStrategyFactory.DynamicContext, String> {

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    protected IOpsMetricGateway metricGateway;

    @Resource
    protected IOpsLogGateway logGateway;

    @Resource
    protected IOpsTraceGateway traceGateway;

    @Resource
    protected IOpsRunbookGateway runbookGateway;

    @Resource
    protected IOpsIncidentRepository incidentRepository;

    @Resource
    protected IOpsGovernanceRepository governanceRepository;

    @Resource
    protected OpsSensitiveDataMasker sensitiveDataMasker;

    @Resource
    protected OpsAgentPlanExecutionService planExecutionService;

    @Resource
    protected OpsToolGovernanceService toolGovernanceService;

    @Resource
    protected OpsIncidentWorkingMemoryService workingMemoryService;

    @Resource
    protected OpsHistoricalIncidentMemoryService historicalIncidentMemoryService;

    @Resource(name = "openAiChatModel")
    protected ChatModel chatModelProvider;

    @Override
    protected void multiThread(IncidentCommandEntity requestParameter,
                               DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext)
            throws ExecutionException, InterruptedException, TimeoutException {
    }

    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    protected void send(DefaultOpsAgentExecuteStrategyFactory.DynamicContext context, OpsAnalyzeEventEntity event) {
        try {
            event.setContent(mask(event.getContent()));
            ResponseBodyEmitter emitter = context.getEmitter();
            if (emitter != null) {
                emitter.send("data: " + JSON.toJSONString(event) + "\n\n");
            }
        } catch (IOException e) {
            log.warn("Send ops agent SSE event failed. sessionId={}", event.getSessionId(), e);
        }
    }

    protected List<RootCauseCandidateEntity> buildRootCauseCandidates(IncidentCommandEntity command,
                                                                      MetricEvidenceEntity metricEvidence,
                                                                      LogEvidenceEntity logEvidence,
                                                                      TraceEvidenceEntity traceEvidence) {
        List<RootCauseCandidateEntity> candidates = new ArrayList<>();
        boolean http5xxMetric = hasMetricAnomaly(metricEvidence, "http_5xx_detected", "http_5xx_rate_high");
        boolean latencyMetric = hasMetricAnomaly(metricEvidence, "avg_latency_high", "p95_latency_high", "p99_latency_high");
        boolean hikariMetric = hasMetricAnomaly(metricEvidence, "hikari_pool_high_usage", "hikari_pending_connections", "hikari_connection_timeout");
        boolean resourceMetric = hasMetricAnomaly(metricEvidence, "cpu_usage_high", "gc_pause_high");

        boolean databaseLog = hasLogSignal(logEvidence, "sqltimeoutexception", "connection is not available",
                "communications link failure", "hikari", "jdbc", "mysql", "slow sql");
        boolean databaseTrace = hasTraceSignal(traceEvidence, "db", "database", "jdbc", "mysql", "sql", "hikari");
        boolean downstreamLog = !databaseLog && hasLogSignal(logEvidence, "read timed out", "connect timed out", "504",
                "dubbo", "rpc", "feign", "resttemplate", "webclient", "gateway", "downstream");
        boolean downstreamTrace = !databaseTrace && hasTraceSignal(traceEvidence,
                "dubbo", "feign", "resttemplate", "webclient", "gateway", "downstream", "httpclient", "rpc", "504");
        boolean applicationLog = !databaseLog && !downstreamLog && hasLogSignal(logEvidence, "nullpointerexception", "illegalargumentexception",
                "indexoutofboundsexception", "classcastexception", "exception", "stack_trace", " 500 ");
        boolean applicationTrace = !databaseTrace && !downstreamTrace && hasTraceSignal(traceEvidence,
                "\"iserror\":true", "iserror true", "exception", "throwable");

        if (hikariMetric || databaseLog || databaseTrace) {
            candidates.add(RootCauseCandidateEntity.builder()
                    .cause("Database or connection pool failure caused request errors")
                    .category("database")
                    .confidence(scoreFromSignals(45, hikariMetric, databaseLog, databaseTrace))
                    .reasoning("Database, JDBC, connection-pool, or SQL timeout signals were found in Prometheus anomalies, ELK logs, or SkyWalking traces.")
                    .evidences(databaseEvidences(command, metricEvidence, logEvidence, traceEvidence))
                    .remediationSuggestions(List.of(
                            "Check Hikari active/idle/max connections, connection wait time, and slow SQL.",
                            "Check database CPU, IO, locks, and connection count in the incident window.",
                            "Apply temporary traffic limiting only after confirming database capacity."))
                    .build());
        }

        if (downstreamLog || downstreamTrace || (latencyMetric && http5xxMetric && !databaseLog && !databaseTrace)) {
            candidates.add(RootCauseCandidateEntity.builder()
                    .cause("Downstream dependency timeout or trace failure")
                    .category("downstream")
                    .confidence(scoreFromSignals(32, latencyMetric && http5xxMetric, downstreamLog, downstreamTrace))
                    .reasoning("Timeout, RPC, HTTP client, or downstream error signals were found in logs, traces, or synchronized latency/error metrics.")
                    .evidences(downstreamEvidences(command, metricEvidence, logEvidence, traceEvidence))
                    .remediationSuggestions(List.of(
                            "Use SkyWalking to locate the slowest or error downstream span.",
                            "Check downstream error rate, P95/P99 latency, thread pool, and instance health.",
                            "Apply circuit breaking, fallback, or retry backoff to avoid cascading failure."))
                    .build());
        }

        if (applicationLog || applicationTrace || (http5xxMetric && !databaseLog && !databaseTrace && !downstreamLog && !downstreamTrace)) {
            candidates.add(RootCauseCandidateEntity.builder()
                    .cause("Application exception or invalid input caused 500 errors")
                    .category("application")
                    .confidence(scoreFromSignals(30, http5xxMetric, applicationLog, applicationTrace))
                    .reasoning("500, exception, stack trace, or application error span signals were found. The stack top and request parameters should be checked first.")
                    .evidences(applicationEvidences(command, metricEvidence, logEvidence, traceEvidence))
                    .remediationSuggestions(List.of(
                            "Extract top stack frame, class, method, line number, and traceId from ELK.",
                            "Check deployments, configuration changes, and abnormal input traffic in the incident window.",
                            "Rollback or patch confirmed code defects; add validation for parameter issues."))
                    .build());
        }

        if (http5xxMetric || latencyMetric || hikariMetric || resourceMetric) {
            candidates.add(RootCauseCandidateEntity.builder()
                    .cause("Resource or performance metric anomaly reduced service stability")
                    .category("metric_anomaly")
                    .confidence(scoreFromSignals(35, true, false, false))
                    .reasoning("Prometheus reported one or more real ANOMALY observations. OK and NO_DATA metrics are not treated as root-cause evidence.")
                    .evidences(metricAnomalyEvidences(command, metricEvidence))
                    .remediationSuggestions(List.of(
                            "Compare QPS, error rate, P95/P99, CPU, memory, GC, and thread pool metrics before and during the incident.",
                            "Reduce entry traffic or scale service instances if metrics degrade at the same time.",
                            "Persist these metric thresholds as alerting rules for future automated diagnosis."))
                    .build());
        }

        if (candidates.isEmpty()) {
            candidates.add(insufficientEvidenceCandidate(metricEvidence, logEvidence, traceEvidence));
        }

        candidates.forEach(candidate -> {
            if (candidate.getEvidences() == null || candidate.getEvidences().isEmpty()) {
                candidate.setEvidences(List.of(contextEvidence(command, "No external evidence detail is available for this candidate, so it must be treated as a low-confidence hypothesis.")));
                candidate.setConfidence(Math.min(candidate.getConfidence(), 30));
            }
        });

        return candidates.stream()
                .sorted(Comparator.comparing(RootCauseCandidateEntity::getConfidence).reversed())
                .collect(Collectors.toList());
    }

    protected DiagnosisReportResult generateDiagnosisReport(IncidentCommandEntity command,
                                                            MetricEvidenceEntity metricEvidence,
                                                            LogEvidenceEntity logEvidence,
                                                            TraceEvidenceEntity traceEvidence,
                                                            List<RootCauseCandidateEntity> rootCauseCandidates,
                                                            List<RunbookMatchEntity> runbookMatches) {
        ChatModel chatModel = chatModelProvider;
        String fallbackEvidenceReview = buildFallbackEvidenceReview(metricEvidence, logEvidence, traceEvidence, rootCauseCandidates);
        if (chatModel == null) {
            saveToolCallLog(command, "llm_evidence_reviewer", "evidence-reviewer-agent", "chat model is unavailable", fallbackEvidenceReview,
                    null, 0L, false, "ChatModel bean is unavailable");
            saveToolCallLog(command, "llm", "report-writer-agent", "chat model is unavailable", "fallback report will be used",
                    null, 0L, false, "ChatModel bean is unavailable");
            String report = buildFallbackReport(command, metricEvidence, logEvidence, traceEvidence, rootCauseCandidates, runbookMatches);
            return DiagnosisReportResult.fallback(withAiRuntimeHeader(report, false, false, 0L, 0L, "ChatModel bean is unavailable"),
                    fallbackEvidenceReview, "ChatModel bean is unavailable");
        }

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        String evidenceReview = fallbackEvidenceReview;
        boolean reviewerSuccess = false;
        Long reviewerCostMillis = 0L;
        String reviewerError = null;

        try {
            String reviewPrompt = buildEvidenceReviewPrompt(command, metricEvidence, logEvidence, traceEvidence, rootCauseCandidates);
            long start = System.currentTimeMillis();
            String reviewContent = chatClient.prompt(reviewPrompt).call().content();
            reviewerCostMillis = System.currentTimeMillis() - start;
            if (!isBlank(reviewContent)) {
                evidenceReview = reviewContent;
                reviewerSuccess = true;
            } else {
                reviewerError = "Evidence reviewer returned blank content";
            }
            saveToolCallLog(command, "llm_evidence_reviewer", "evidence-reviewer-agent", reviewPrompt, evidenceReview,
                    null, reviewerCostMillis, reviewerSuccess, reviewerError);
        } catch (Exception e) {
            reviewerError = summarizeException(e);
            saveToolCallLog(command, "llm_evidence_reviewer", "evidence-reviewer-agent", "evidence review prompt", fallbackEvidenceReview,
                    null, reviewerCostMillis, false, reviewerError);
            log.warn("LLM evidence review failed, deterministic evidence review will be used. sessionId={}, error={}",
                    command.getSessionId(), reviewerError);
        }

        try {
            String prompt = buildDiagnosisPrompt(command, metricEvidence, logEvidence, traceEvidence, rootCauseCandidates, runbookMatches, evidenceReview);
            long start = System.currentTimeMillis();
            String content = chatClient.prompt(prompt).call().content();
            long reportCostMillis = System.currentTimeMillis() - start;
            saveToolCallLog(command, "llm", "report-writer-agent", prompt, content,
                    null, reportCostMillis, true, null);
            if (isBlank(content)) {
                String fallbackReason = joinFallbackReason(reviewerSuccess ? null : reviewerError, "Report writer returned blank content");
                String report = buildFallbackReport(command, metricEvidence, logEvidence, traceEvidence, rootCauseCandidates, runbookMatches);
                return DiagnosisReportResult.fallback(withAiRuntimeHeader(report, reviewerSuccess, false, reviewerCostMillis, reportCostMillis,
                                fallbackReason),
                        evidenceReview, reviewerSuccess, reviewerCostMillis, reportCostMillis, fallbackReason);
            }
            return DiagnosisReportResult.success(withAiRuntimeHeader(content, reviewerSuccess, true, reviewerCostMillis, reportCostMillis,
                            reviewerSuccess ? null : reviewerError),
                    evidenceReview, reviewerSuccess, reviewerCostMillis, reportCostMillis, reviewerError);
        } catch (Exception e) {
            String reportError = summarizeException(e);
            saveToolCallLog(command, "llm", "report-writer-agent", "diagnosis prompt", null,
                    null, null, false, reportError);
            log.warn("LLM diagnosis failed, fallback report will be used. sessionId={}, error={}",
                    command.getSessionId(), reportError);
            String fallbackReason = joinFallbackReason(reviewerSuccess ? null : reviewerError, reportError);
            String report = buildFallbackReport(command, metricEvidence, logEvidence, traceEvidence, rootCauseCandidates, runbookMatches);
            return DiagnosisReportResult.fallback(withAiRuntimeHeader(report, reviewerSuccess, false, reviewerCostMillis, 0L,
                            fallbackReason),
                    evidenceReview, reviewerSuccess, reviewerCostMillis, 0L, fallbackReason);
        }
    }

    protected void saveDiagnosisRecord(String diagnosisId,
                                       IncidentCommandEntity command,
                                       DefaultOpsAgentExecuteStrategyFactory.DynamicContext context,
                                       String status,
                                       String errorMessage) {
        try {
            LocalDateTime now = LocalDateTime.now();
            DiagnosisRecordEntity record = DiagnosisRecordEntity.builder()
                    .diagnosisId(diagnosisId)
                    .sessionId(command.getSessionId())
                    .serviceName(command.getServiceName())
                    .startTime(command.getStartTime())
                    .endTime(command.getEndTime())
                    .problem(command.getProblem())
                    .traceId(command.getTraceId())
                    .status(status)
                    .requestJson(maskJson(command))
                    .metricEvidenceJson(maskJson(context.getMetricEvidence()))
                    .logEvidenceJson(maskJson(context.getLogEvidence()))
                    .traceEvidenceJson(maskJson(context.getTraceEvidence()))
                    .evidenceChainJson(maskJson(context.getRootCauseCandidates()))
                    .runbookJson(maskJson(context.getRunbookMatches()))
                    .report(mask(context.getReport()))
                    .errorMessage(mask(errorMessage))
                    .createTime(now)
                    .updateTime(now)
                    .build();
            incidentRepository.saveDiagnosisRecord(record);
            if ("SUCCESS".equals(status)) {
                historicalIncidentMemoryService.createFromDiagnosisRecord(record, context);
            }
        } catch (Exception e) {
            log.warn("Save diagnosis review record failed. diagnosisId={}, sessionId={}",
                    diagnosisId, command.getSessionId(), e);
        }
    }

    protected String buildMetricMessage(MetricEvidenceEntity evidence) {
        return String.format("Metric source: %s\nStatus: %s\nSummary: %s\nObservations:\n%s",
                evidence.getSource(), availability(evidence.isAvailable()), evidence.getSummary(), join(evidence.getObservations()));
    }

    protected String buildLogMessage(LogEvidenceEntity evidence) {
        return String.format("Log source: %s\nStatus: %s\nSummary: %s\nError samples:\n%s",
                evidence.getSource(), availability(evidence.isAvailable()), evidence.getSummary(), join(evidence.getErrorSamples()));
    }

    protected String buildTraceMessage(TraceEvidenceEntity evidence) {
        return String.format("Trace source: %s\nStatus: %s\nSummary: %s\nSpan summary:\n%s",
                evidence.getSource(), availability(evidence.isAvailable()), evidence.getSummary(), join(evidence.getSpans()));
    }

    protected String formatEvidenceChain(List<RootCauseCandidateEntity> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "No root-cause candidates.";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            RootCauseCandidateEntity candidate = candidates.get(i);
            builder.append("### Candidate ").append(i + 1).append(": ").append(candidate.getCause()).append("\n")
                    .append("- category: ").append(candidate.getCategory()).append("\n")
                    .append("- conclusion strength: ").append(Boolean.TRUE.equals(candidate.getHypothesis()) ? "probable / hypothesis" : "confirmed").append("\n")
                    .append("- confidence: ").append(candidate.getConfidence()).append("\n")
                    .append("- reasoning: ").append(candidate.getReasoning()).append("\n")
                    .append("- evidence:\n");
            List<EvidenceItemEntity> evidences = candidate.getEvidences();
            if (evidences == null || evidences.isEmpty()) {
                builder.append("  - No evidence. This cannot be treated as a confirmed conclusion.\n");
            } else {
                for (int j = 0; j < evidences.size(); j++) {
                    EvidenceItemEntity evidence = evidences.get(j);
                    builder.append("  ").append(j + 1).append(". [").append(evidence.getSource()).append("/")
                            .append(evidence.getCategory()).append("] ")
                            .append(evidence.getTitle()).append(": ")
                            .append(evidence.getDetail()).append(" (evidence confidence ")
                            .append(evidence.getConfidence()).append(")\n");
                }
            }
            builder.append("- suggestions:\n");
            if (candidate.getRemediationSuggestions() != null) {
                candidate.getRemediationSuggestions().forEach(suggestion -> builder.append("  - ").append(suggestion).append("\n"));
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    protected String formatRunbookMatches(List<RunbookMatchEntity> matches) {
        if (matches == null || matches.isEmpty()) {
            return "No runbook matched. Add or index runbooks under docs/dev-ops/runbook.";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            RunbookMatchEntity match = matches.get(i);
            builder.append("### Runbook ").append(i + 1).append(": ").append(match.getTitle()).append("\n")
                    .append("- id: ").append(match.getRunbookId()).append("\n")
                    .append("- category: ").append(match.getCategory()).append("\n")
                    .append("- score: ").append(match.getScore()).append("\n")
                    .append("- path: ").append(match.getPath()).append("\n")
                    .append("- summary: ").append(match.getSummary()).append("\n\n");
        }
        return builder.toString();
    }

    protected OpsToolGovernanceDecision requestToolAccess(IncidentCommandEntity command,
                                                          DefaultOpsAgentExecuteStrategyFactory.DynamicContext context,
                                                          String toolName,
                                                          String agentRole,
                                                          String intent) {
        OpsToolGovernanceDecision decision = toolGovernanceService.evaluate(command, context, toolName, agentRole, intent);
        if (!decision.isAllowed()) {
            planExecutionService.recordDecision(command, context, toolName, agentRole, "DENIED", decision.getReason());
            saveToolCallLog(command, toolName, agentRole, intent, decision.getReason(), 429, 0L, false, decision.getReason());
        }
        return decision;
    }

    private List<EvidenceItemEntity> databaseEvidences(IncidentCommandEntity command,
                                                       MetricEvidenceEntity metricEvidence,
                                                       LogEvidenceEntity logEvidence,
                                                       TraceEvidenceEntity traceEvidence) {
        List<EvidenceItemEntity> evidences = new ArrayList<>();
        addIfAvailable(evidences, "elk", "log", "Database error log",
                available(logEvidence), matchSignal(logEvidence == null ? null : logEvidence.getRawData(), logEvidence == null ? null : logEvidence.getSummary(),
                        "hikari", "sql", "jdbc", "mysql", "connection", "database", "timeout"), 85);
        addIfAvailable(evidences, "prometheus", "metric", "Database or pool metric",
                available(metricEvidence), anomalyObservations(metricEvidence,
                        "hikari_pool_high_usage", "hikari_pending_connections", "hikari_connection_timeout"), 70);
        addIfAvailable(evidences, "skywalking", "trace", "Database trace span",
                available(traceEvidence), matchSignal(traceEvidence == null ? null : traceEvidence.getRawData(), traceEvidence == null ? null : traceEvidence.getSummary(),
                        "jdbc", "mysql", "database", "db", "sql"), 80);
        if (evidences.isEmpty()) {
            evidences.add(contextEvidence(command, "Database-related signal exists, but no direct external evidence detail is available."));
        }
        return evidences;
    }

    private List<EvidenceItemEntity> downstreamEvidences(IncidentCommandEntity command,
                                                         MetricEvidenceEntity metricEvidence,
                                                         LogEvidenceEntity logEvidence,
                                                         TraceEvidenceEntity traceEvidence) {
        List<EvidenceItemEntity> evidences = new ArrayList<>();
        addIfAvailable(evidences, "elk", "log", "Downstream timeout log",
                available(logEvidence), matchSignal(logEvidence == null ? null : logEvidence.getRawData(), logEvidence == null ? null : logEvidence.getSummary(),
                        "timeout", "dubbo", "rpc", "feign", "resttemplate", "webclient", "504"), 82);
        addIfAvailable(evidences, "skywalking", "trace", "Downstream error span",
                available(traceEvidence), matchSignal(traceEvidence == null ? null : traceEvidence.getRawData(), traceEvidence == null ? null : traceEvidence.getSummary(),
                        "iserror", "peer", "endpoint", "timeout", "rpc", "http"), 84);
        addIfAvailable(evidences, "prometheus", "metric", "Latency or error-rate metric",
                available(metricEvidence), anomalyObservations(metricEvidence,
                        "avg_latency_high", "p95_latency_high", "p99_latency_high", "http_5xx_detected", "http_5xx_rate_high"), 62);
        if (evidences.isEmpty()) {
            evidences.add(contextEvidence(command, "Downstream-related signal exists, but no direct external evidence detail is available."));
        }
        return evidences;
    }

    private List<EvidenceItemEntity> applicationEvidences(IncidentCommandEntity command,
                                                          MetricEvidenceEntity metricEvidence,
                                                          LogEvidenceEntity logEvidence,
                                                          TraceEvidenceEntity traceEvidence) {
        List<EvidenceItemEntity> evidences = new ArrayList<>();
        addIfAvailable(evidences, "elk", "log", "Application exception log",
                available(logEvidence), matchSignal(logEvidence == null ? null : logEvidence.getRawData(), logEvidence == null ? null : logEvidence.getSummary(),
                        "exception", "stack_trace", "nullpointer", "illegalargument", "500"), 88);
        addIfAvailable(evidences, "skywalking", "trace", "Application error span",
                available(traceEvidence), matchSignal(traceEvidence == null ? null : traceEvidence.getRawData(), traceEvidence == null ? null : traceEvidence.getSummary(),
                        "iserror", "logs", "endpointname", "servicecode"), 75);
        addIfAvailable(evidences, "prometheus", "metric", "Error-rate metric",
                available(metricEvidence), anomalyObservations(metricEvidence,
                        "http_5xx_detected", "http_5xx_rate_high"), 60);
        evidences.add(contextEvidence(command, "User problem description: " + command.getProblem()));
        return evidences;
    }

    private List<EvidenceItemEntity> metricAnomalyEvidences(IncidentCommandEntity command, MetricEvidenceEntity metricEvidence) {
        List<EvidenceItemEntity> evidences = new ArrayList<>();
        addIfAvailable(evidences, "prometheus", "metric", "Metric collection result",
                available(metricEvidence), metricEvidence.getSummary() + "\n" + anomalyObservations(metricEvidence), 78);
        if (evidences.isEmpty()) {
            evidences.add(contextEvidence(command, "Metric data is unavailable, so resource or performance anomaly cannot be confirmed."));
        }
        return evidences;
    }

    private RootCauseCandidateEntity insufficientEvidenceCandidate(MetricEvidenceEntity metricEvidence,
                                                                   LogEvidenceEntity logEvidence,
                                                                   TraceEvidenceEntity traceEvidence) {
        return RootCauseCandidateEntity.builder()
                .cause("Insufficient evidence for a high-confidence root cause")
                .category("insufficient_evidence")
                .confidence(25)
                .reasoning("Available metrics, logs, and traces are not enough for a reliable final conclusion.")
                .evidences(List.of(
                        EvidenceItemEntity.builder().source("prometheus").category("metric").title("Metric data status").detail(summary(metricEvidence)).confidence(available(metricEvidence) ? 50 : 20).build(),
                        EvidenceItemEntity.builder().source("elk").category("log").title("Log data status").detail(summary(logEvidence)).confidence(available(logEvidence) ? 50 : 20).build(),
                        EvidenceItemEntity.builder().source("skywalking").category("trace").title("Trace data status").detail(summary(traceEvidence)).confidence(available(traceEvidence) ? 50 : 20).build()))
                .remediationSuggestions(List.of(
                        "Configure Prometheus, ELK, and SkyWalking integrations first.",
                        "Provide traceId, endpoint path, error code, and an accurate incident window.",
                        "Add deployment records, configuration changes, and alert screenshots."))
                .build();
    }

    private String buildDiagnosisPrompt(IncidentCommandEntity command,
                                        MetricEvidenceEntity metricEvidence,
                                        LogEvidenceEntity logEvidence,
                                        TraceEvidenceEntity traceEvidence,
                                        List<RootCauseCandidateEntity> rootCauseCandidates,
                                        List<RunbookMatchEntity> runbookMatches,
                                        String evidenceReview) {
        return String.format("""
                You are Report Writer Agent in a multi-agent SRE diagnosis workflow. Produce a production incident review report based only on user input, Prometheus/Grafana metrics, ELK logs, SkyWalking traces, the structured evidence chain, and the Evidence Reviewer Agent output.

                Strict rules:
                1. Answer in Chinese with clear sections for engineering collaboration.
                2. Include: incident summary, confirmed facts, impact scope, timeline, root-cause candidates, evidence chain, temporary mitigation, long-term improvements, missing data, and to-be-verified hypotheses.
                3. Every confirmed conclusion must cite evidence from evidence_chain or Evidence Reviewer Agent output.
                4. Any unsupported idea must be placed under "待验证假设"; do not put it under confirmed facts or root cause.
                5. Use runbook_context only as remediation knowledge. Do not treat runbook text as incident evidence.
                6. If a data source is unavailable or returns empty data, state that clearly and do not fabricate evidence.
                7. Do not claim affected users, business loss, deployment changes, upstream/downstream failure, or final root cause unless direct evidence exists.
                8. Rank root-cause candidates by confidence and explicitly mention when a candidate is only a hypothesis.
                9. If traceId is not provided but SkyWalking service-level metrics or trace samples exist, write "缺少精确 traceId，已有服务级 trace 样本"; do not write "缺少 trace 证据".
                10. Distinguish confirmed failure mode from underlying cause. For example, SQLTimeoutException and "Connection is not available" confirm database connection acquisition timeout; pool size, database capacity, slow SQL, and connection leak are to-be-verified underlying causes unless directly evidenced.
                11. Do not repeat the same point in both confirmed facts and to-be-verified hypotheses. If a failure mode is confirmed, put only the deeper unverified reasons under "待验证假设".

                12. If impact evidence is missing, write "影响范围：未采集到受影响用户数或业务损失证据". Do not infer impact with words like "推测", "可能影响", or "导致用户请求失败" unless direct impact evidence exists.

                User problem:
                - service: %s
                - window: %s ~ %s
                - problem: %s
                - traceId: %s

                Evidence Reviewer Agent output:
                %s

                Structured root-cause candidates and evidence_chain:
                %s

                Retrieved runbook_context:
                %s

                Raw Prometheus/Grafana evidence:
                %s

                Raw ELK evidence:
                %s

                Raw SkyWalking evidence:
                %s
                """,
                command.getServiceName(),
                command.getStartTime(),
                command.getEndTime(),
                mask(command.getProblem()),
                mask(blankToDefault(command.getTraceId(), "not provided")),
                mask(evidenceReview),
                maskJson(rootCauseCandidates),
                maskJson(runbookMatches),
                maskJson(metricEvidence),
                maskJson(logEvidence),
                maskJson(traceEvidence));
    }

    private String buildEvidenceReviewPrompt(IncidentCommandEntity command,
                                             MetricEvidenceEntity metricEvidence,
                                             LogEvidenceEntity logEvidence,
                                             TraceEvidenceEntity traceEvidence,
                                             List<RootCauseCandidateEntity> rootCauseCandidates) {
        return String.format("""
                You are Evidence Reviewer Agent in a multi-agent SRE diagnosis workflow. Your job is to audit evidence quality before the final report is written.

                Output in Chinese with these sections only:
                1. 已确认事实
                2. 高可信证据
                3. 弱证据或缺失数据
                4. 不能写成确定结论的内容
                5. 给 Report Writer Agent 的约束

                Hard rules:
                - Only use the provided Prometheus, ELK, SkyWalking and evidence_chain data.
                - Do not use runbook knowledge as incident evidence.
                - If SkyWalking has serviceId, service metrics, error trace samples, or slow trace samples, treat it as available service-level trace evidence.
                - If traceId is not provided, say only that precise single-trace lookup is missing; do not say SkyWalking trace evidence is missing when service-level trace samples exist.
                - If SkyWalking has no trace/span/service data at all, say it is missing trace evidence.
                - Treat the user's problem description as incident context, not as confirmed evidence. For example, "大量 500" is confirmed only when Prometheus 5xx metrics, ELK error samples, or trace error samples support it.
                - If a conclusion lacks direct evidence, explicitly mark it as "只能作为待验证假设".
                - Do not invent affected users, business loss, deployment changes, downstream service names, or final root cause.
                - Distinguish confirmed failure mode from underlying cause. SQLTimeoutException and "Connection is not available" confirm database connection acquisition timeout; pool configuration, database capacity, slow SQL, and connection leak still need separate evidence.
                - Do not put an already confirmed failure mode into "不能写成确定结论的内容"; only put unproven deeper causes there.

                User problem:
                service=%s
                window=%s ~ %s
                problem=%s
                traceId=%s

                Structured evidence_chain:
                %s

                Raw Prometheus/Grafana evidence:
                %s

                Raw ELK evidence:
                %s

                Raw SkyWalking evidence:
                %s
                """,
                command.getServiceName(),
                command.getStartTime(),
                command.getEndTime(),
                mask(command.getProblem()),
                mask(blankToDefault(command.getTraceId(), "not provided")),
                maskJson(rootCauseCandidates),
                maskJson(metricEvidence),
                maskJson(logEvidence),
                maskJson(traceEvidence));
    }

    private String buildFallbackEvidenceReview(MetricEvidenceEntity metricEvidence,
                                               LogEvidenceEntity logEvidence,
                                               TraceEvidenceEntity traceEvidence,
                                               List<RootCauseCandidateEntity> rootCauseCandidates) {
        return String.format("""
                ## 已确认事实
                - Prometheus 数据状态：%s。%s
                - ELK 数据状态：%s。%s
                - SkyWalking 数据状态：%s。%s

                ## 高可信证据
                %s

                ## 弱证据或缺失数据
                - 对没有外部证据支撑的推断，只能作为待验证假设。
                - runbook 只能用于处理建议，不能作为事故发生证据。

                ## 不能写成确定结论的内容
                - 未采集到直接证据时，不能确认影响用户数、业务损失、发布变更、下游服务名或最终根因。

                ## 给 Report Writer Agent 的约束
                - 只引用 evidence_chain、Prometheus、ELK、SkyWalking 中实际存在的证据。
                - 缺失数据必须单独列出，不能用经验判断补齐。
                """,
                availability(available(metricEvidence)),
                summary(metricEvidence),
                availability(available(logEvidence)),
                summary(logEvidence),
                availability(available(traceEvidence)),
                summary(traceEvidence),
                formatEvidenceChain(rootCauseCandidates));
    }

    protected String buildEvidenceReviewerMessage(DiagnosisReportResult result) {
        if (!result.isEvidenceReviewerSuccess()) {
            return String.format("Evidence Reviewer Agent used deterministic fallback. cost=%sms, reason=%s",
                    result.getEvidenceReviewerCostMillis(),
                    blankToDefault(result.getFallbackReason(), "unknown"));
        }
        return String.format("Evidence Reviewer Agent %s. cost=%sms\n%s",
                "completed with LLM",
                result.getEvidenceReviewerCostMillis(),
                abbreviate(result.getEvidenceReview(), 1200));
    }

    protected String buildReportWriterMessage(DiagnosisReportResult result) {
        if (result.isReportWriterSuccess()) {
            return "Report Writer Agent completed with LLM. cost=" + result.getReportWriterCostMillis() + "ms";
        }
        return "Report Writer Agent used fallback report. reason=" + blankToDefault(result.getFallbackReason(), "unknown");
    }

    private String withAiRuntimeHeader(String report,
                                       boolean evidenceReviewerSuccess,
                                       boolean reportWriterSuccess,
                                       Long evidenceReviewerCostMillis,
                                       Long reportWriterCostMillis,
                                       String fallbackReason) {
        return String.format("""
                ## AI 调用状态
                - Evidence Reviewer Agent: %s, cost=%sms
                - Report Writer Agent: %s, cost=%sms
                - 证据约束：最终报告只能引用 Prometheus、ELK、SkyWalking、evidence_chain 中真实存在的证据；runbook 只作为处置建议来源。
                - Fallback reason: %s

                %s
                """,
                evidenceReviewerSuccess ? "LLM_SUCCESS" : "FALLBACK",
                evidenceReviewerCostMillis == null ? 0 : evidenceReviewerCostMillis,
                reportWriterSuccess ? "LLM_SUCCESS" : "FALLBACK",
                reportWriterCostMillis == null ? 0 : reportWriterCostMillis,
                blankToDefault(fallbackReason, "none"),
                report);
    }

    private String buildFallbackReport(IncidentCommandEntity command,
                                       MetricEvidenceEntity metricEvidence,
                                       LogEvidenceEntity logEvidence,
                                       TraceEvidenceEntity traceEvidence,
                                       List<RootCauseCandidateEntity> rootCauseCandidates,
                                       List<RunbookMatchEntity> runbookMatches) {
        return String.format("""
                ## Ops Incident Diagnosis Report

                ### Incident Summary
                Service `%s` had an incident in `%s ~ %s`: %s

                ### Data Collection Status
                - Metrics: %s
                - Logs: %s
                - Traces: %s

                ### Root Cause Candidates With Evidence
                %s

                ### Retrieved Runbooks
                %s

                ### Temporary Mitigation
                - Start with the highest-confidence candidate and verify each evidence item.
                - Apply traffic limiting, fallback, or rollback for the affected endpoint to prevent blast radius expansion.
                - If evidence points to database or downstream dependency, isolate slow calls and inspect pool, slow SQL, and dependency health.

                ### Long-Term Improvements
                - Persist alert rules for error rate, P95/P99 latency, pool usage, thread pool, GC, and downstream latency.
                - Persist the evidence chain, final root cause, and remediation actions for incident review.
                - Keep PgVector runbook RAG, ELK, and SkyWalking data as mandatory evidence sources in production diagnosis.
                """,
                command.getServiceName(),
                command.getStartTime(),
                command.getEndTime(),
                mask(command.getProblem()),
                availability(available(metricEvidence)),
                availability(available(logEvidence)),
                availability(available(traceEvidence)),
                formatEvidenceChain(rootCauseCandidates),
                formatRunbookMatches(runbookMatches));
    }

    private void addIfAvailable(List<EvidenceItemEntity> evidences, String source, String category, String title,
                                boolean available, String detail, int confidence) {
        if (!available || isBlank(detail)) {
            return;
        }
        evidences.add(EvidenceItemEntity.builder()
                .source(source)
                .category(category)
                .title(title)
                .detail(abbreviate(detail, 800))
                .confidence(confidence)
                .build());
    }

    private EvidenceItemEntity contextEvidence(IncidentCommandEntity command, String detail) {
        return EvidenceItemEntity.builder()
                .source("user_input")
                .category("context")
                .title("User incident description")
                .detail(String.format("service=%s, window=%s ~ %s, detail=%s",
                        command.getServiceName(), command.getStartTime(), command.getEndTime(), detail))
                .confidence(35)
                .build();
    }

    private int score(int base, MetricEvidenceEntity metricEvidence, LogEvidenceEntity logEvidence, TraceEvidenceEntity traceEvidence) {
        int score = base;
        if (available(metricEvidence)) score += 4;
        if (available(logEvidence)) score += 8;
        if (available(traceEvidence)) score += 8;
        return Math.min(score, 95);
    }

    private int scoreFromSignals(int base, boolean metricAnomaly, boolean logSignal, boolean traceSignal) {
        int score = base;
        if (metricAnomaly) score += 12;
        if (logSignal) score += 18;
        if (traceSignal) score += 18;

        int cap = 95;
        if (!logSignal && !traceSignal) {
            cap = 60;
        }
        if (!metricAnomaly && !logSignal && !traceSignal) {
            cap = 30;
        }
        return Math.min(score, cap);
    }

    private boolean hasMetricAnomaly(MetricEvidenceEntity metricEvidence, String... keywords) {
        if (!available(metricEvidence) || metricEvidence.getObservations() == null) {
            return false;
        }
        return metricEvidence.getObservations().stream()
                .filter(Objects::nonNull)
                .map(item -> item.toLowerCase(Locale.ROOT))
                .filter(item -> item.startsWith("anomaly:"))
                .anyMatch(item -> containsAny(item, keywords));
    }

    private boolean hasLogSignal(LogEvidenceEntity logEvidence, String... keywords) {
        if (!available(logEvidence)) {
            return false;
        }
        return hasSignal(join(logEvidence.getErrorSamples()) + "\n" + value(logEvidence.getRawData()), keywords);
    }

    private boolean hasTraceSignal(TraceEvidenceEntity traceEvidence, String... keywords) {
        if (!available(traceEvidence)) {
            return false;
        }
        return hasSignal(join(traceEvidence.getSpans()) + "\n" + value(traceEvidence.getRawData()), keywords);
    }

    private boolean hasSignal(String text, String... keywords) {
        if (isBlank(text) || keywords == null || keywords.length == 0) {
            return false;
        }
        return containsAny(text.toLowerCase(Locale.ROOT), keywords);
    }

    private String buildSearchText(IncidentCommandEntity command,
                                   MetricEvidenceEntity metricEvidence,
                                   LogEvidenceEntity logEvidence,
                                   TraceEvidenceEntity traceEvidence) {
        return String.join("\n",
                value(command.getProblem()),
                summary(metricEvidence),
                metricEvidence == null ? "" : join(metricEvidence.getObservations()),
                metricEvidence == null ? "" : value(metricEvidence.getRawData()),
                summary(logEvidence),
                logEvidence == null ? "" : join(logEvidence.getErrorSamples()),
                logEvidence == null ? "" : value(logEvidence.getRawData()),
                summary(traceEvidence),
                traceEvidence == null ? "" : join(traceEvidence.getSpans()),
                traceEvidence == null ? "" : value(traceEvidence.getRawData()));
    }

    private String anomalyObservations(MetricEvidenceEntity metricEvidence, String... keywords) {
        if (metricEvidence == null || metricEvidence.getObservations() == null || metricEvidence.getObservations().isEmpty()) {
            return "";
        }
        String keywordText = keywords == null ? "" : String.join("\n", keywords).toLowerCase(Locale.ROOT);
        return metricEvidence.getObservations().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.toLowerCase(Locale.ROOT).startsWith("anomaly:"))
                .filter(item -> isBlank(keywordText) || containsAny(item.toLowerCase(Locale.ROOT), keywords))
                .map(item -> "- " + item)
                .collect(Collectors.joining("\n"));
    }

    private String matchOrSummary(String rawData, String summary, String... keywords) {
        String raw = rawData == null ? "" : rawData;
        String lowerRaw = raw.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            int index = lowerRaw.indexOf(keyword.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                int start = Math.max(0, index - 180);
                int end = Math.min(raw.length(), index + 420);
                return raw.substring(start, end);
            }
        }
        return summary;
    }

    private String matchSignal(String rawData, String summary, String... keywords) {
        String raw = rawData == null ? "" : rawData;
        String lowerRaw = raw.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            int index = lowerRaw.indexOf(keyword.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                int start = Math.max(0, index - 180);
                int end = Math.min(raw.length(), index + 420);
                return raw.substring(start, end);
            }
        }
        return "";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        if (keywords == null) return false;
        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
        return evidence == null ? "" : value(evidence.getSummary());
    }

    private String summary(LogEvidenceEntity evidence) {
        return evidence == null ? "" : value(evidence.getSummary());
    }

    private String summary(TraceEvidenceEntity evidence) {
        return evidence == null ? "" : value(evidence.getSummary());
    }

    private String join(List<String> items) {
        if (items == null || items.isEmpty()) return "- none";
        return items.stream().filter(Objects::nonNull).map(item -> "- " + item).collect(Collectors.joining("\n"));
    }

    private String availability(boolean available) {
        return available ? "available" : "unavailable/not configured";
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private String joinFallbackReason(String reviewerError, String reportError) {
        if (isBlank(reviewerError)) {
            return reportError;
        }
        if (isBlank(reportError)) {
            return "Evidence Reviewer Agent fallback: " + reviewerError;
        }
        return "Evidence Reviewer Agent fallback: " + reviewerError + "; Report Writer Agent fallback: " + reportError;
    }

    private String summarizeException(Exception e) {
        if (e == null) {
            return "unknown";
        }
        String message = e.getMessage();
        Throwable cause = e.getCause();
        while (cause != null) {
            if (!isBlank(cause.getMessage()) && !cause.getMessage().equals(message)) {
                message = blankToDefault(message, e.getClass().getSimpleName()) + "; cause=" + cause.getMessage();
                break;
            }
            cause = cause.getCause();
        }
        return abbreviate(blankToDefault(message, e.getClass().getSimpleName()), 500);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String maskJson(Object value) {
        return value == null ? null : mask(JSON.toJSONString(value));
    }

    private String mask(String value) {
        return sensitiveDataMasker.mask(value);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    protected void saveToolCallLog(IncidentCommandEntity command, String toolName, String target, String requestSummary,
                                   String responseSummary, Integer statusCode, Long costMillis, boolean success, String errorMessage) {
        try {
            governanceRepository.saveToolCallLog(OpsToolCallLogEntity.builder()
                    .callId("tool-" + UUID.randomUUID())
                    .sessionId(command.getSessionId())
                    .diagnosisId(command.getDiagnosisId())
                    .toolName(toolName)
                    .logicalToolName(OpsToolProtocolResolver.logicalToolNameOf(toolName, target))
                    .protocol(OpsToolProtocolResolver.protocolOf(toolName, target))
                    .governanceDecision(success ? "SUCCESS" : "FAILED")
                    .target(abbreviate(mask(target), 256))
                    .requestSummary(abbreviate(mask(requestSummary), 6000))
                    .responseSummary(abbreviate(mask(responseSummary), 6000))
                    .statusCode(statusCode)
                    .costMillis(costMillis)
                    .success(Boolean.toString(success))
                    .errorMessage(abbreviate(mask(errorMessage), 2000))
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Save ops tool call log failed. toolName={}, sessionId={}", toolName, command.getSessionId(), e);
        }
    }

    protected static class DiagnosisReportResult {

        private final String report;
        private final String evidenceReview;
        private final boolean evidenceReviewerSuccess;
        private final boolean reportWriterSuccess;
        private final Long evidenceReviewerCostMillis;
        private final Long reportWriterCostMillis;
        private final String fallbackReason;

        private DiagnosisReportResult(String report,
                                      String evidenceReview,
                                      boolean evidenceReviewerSuccess,
                                      boolean reportWriterSuccess,
                                      Long evidenceReviewerCostMillis,
                                      Long reportWriterCostMillis,
                                      String fallbackReason) {
            this.report = report;
            this.evidenceReview = evidenceReview;
            this.evidenceReviewerSuccess = evidenceReviewerSuccess;
            this.reportWriterSuccess = reportWriterSuccess;
            this.evidenceReviewerCostMillis = evidenceReviewerCostMillis;
            this.reportWriterCostMillis = reportWriterCostMillis;
            this.fallbackReason = fallbackReason;
        }

        static DiagnosisReportResult success(String report,
                                             String evidenceReview,
                                             boolean evidenceReviewerSuccess,
                                             Long evidenceReviewerCostMillis,
                                             Long reportWriterCostMillis,
                                             String reviewerFallbackReason) {
            return new DiagnosisReportResult(report, evidenceReview, evidenceReviewerSuccess, true,
                    evidenceReviewerCostMillis, reportWriterCostMillis, reviewerFallbackReason);
        }

        static DiagnosisReportResult fallback(String report, String evidenceReview, String fallbackReason) {
            return new DiagnosisReportResult(report, evidenceReview, false, false, 0L, 0L, fallbackReason);
        }

        static DiagnosisReportResult fallback(String report,
                                              String evidenceReview,
                                              boolean evidenceReviewerSuccess,
                                              Long evidenceReviewerCostMillis,
                                              Long reportWriterCostMillis,
                                              String fallbackReason) {
            return new DiagnosisReportResult(report, evidenceReview, evidenceReviewerSuccess, false,
                    evidenceReviewerCostMillis, reportWriterCostMillis, fallbackReason);
        }

        public String getReport() {
            return report;
        }

        public String getEvidenceReview() {
            return evidenceReview;
        }

        public boolean isEvidenceReviewerSuccess() {
            return evidenceReviewerSuccess;
        }

        public boolean isReportWriterSuccess() {
            return reportWriterSuccess;
        }

        public Long getEvidenceReviewerCostMillis() {
            return evidenceReviewerCostMillis;
        }

        public Long getReportWriterCostMillis() {
            return reportWriterCostMillis;
        }

        public String getFallbackReason() {
            return fallbackReason;
        }

    }

}


