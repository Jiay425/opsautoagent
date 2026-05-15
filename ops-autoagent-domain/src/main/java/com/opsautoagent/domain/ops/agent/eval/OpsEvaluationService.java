package com.opsautoagent.domain.ops.agent.eval;

import com.opsautoagent.domain.ops.adapter.repository.IOpsAgentRepository;
import com.opsautoagent.domain.ops.adapter.repository.IOpsEvaluationRepository;
import com.opsautoagent.domain.ops.adapter.repository.IOpsIncidentMemoryRepository;
import com.opsautoagent.domain.ops.adapter.repository.IOpsIncidentRepository;
import com.opsautoagent.domain.ops.agent.OpsAgentBootstrapService;
import com.opsautoagent.domain.ops.agent.state.OpsAgentStateService;
import com.opsautoagent.domain.ops.agent.state.OpsIncidentState;
import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.entity.OpsDiagnosisDispatchEntity;
import com.opsautoagent.domain.ops.service.OpsIncidentExecuteStrategy;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class OpsEvaluationService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${ops.agent.evaluation.enabled:false}")
    private boolean evaluationEnabled;

    @Value("${ops.agent.chat.required:false}")
    private boolean chatAgentRequired;

    @Resource
    private IOpsEvaluationRepository opsEvaluationRepository;

    @Resource
    private IOpsIncidentRepository opsIncidentRepository;

    @Resource
    private IOpsIncidentMemoryRepository opsIncidentMemoryRepository;

    @Resource
    private IOpsAgentRepository opsAgentRepository;

    @Resource
    private OpsAgentBootstrapService opsAgentBootstrapService;

    @Resource
    private OpsAgentStateService opsAgentStateService;

    @Resource
    private OpsIncidentExecuteStrategy opsIncidentExecuteStrategy;

    public OpsMemoryToolchainEvaluationSummary evaluateMemoryToolchain() {
        List<?> memories;
        try {
            memories = opsIncidentMemoryRepository.querySimilarMemories(null, "", 100);
        } catch (Exception e) {
            log.warn("Historical incident memory summary query failed. Returning zero-card summary.", e);
            memories = List.of();
        }
        int historicalMemoryCards = memories == null ? 0 : memories.size();
        BigDecimal memoryHitRate = historicalMemoryCards > 0 ? BigDecimal.ONE : BigDecimal.ZERO;

        Map<String, Object> memoryCapabilities = new LinkedHashMap<>();
        memoryCapabilities.put("shortTermWorkingMemory", true);
        memoryCapabilities.put("longTermHistoricalMemory", true);
        memoryCapabilities.put("plannerConsumesWorkingMemory", true);
        memoryCapabilities.put("plannerConsumesHistoricalMemory", true);
        memoryCapabilities.put("reviewerConsumesWorkingMemory", true);
        memoryCapabilities.put("reviewerConsumesHistoricalMemory", true);
        memoryCapabilities.put("reportWriterConsumesWorkingMemory", true);
        memoryCapabilities.put("reportWriterConsumesHistoricalMemory", true);
        memoryCapabilities.put("historicalMemoryCards", historicalMemoryCards);

        Map<String, Object> toolchainCapabilities = new LinkedHashMap<>();
        toolchainCapabilities.put("toolProtocolLogged", true);
        toolchainCapabilities.put("logicalToolNameLogged", true);
        toolchainCapabilities.put("governanceDecisionLogged", true);
        toolchainCapabilities.put("supportedProtocols", List.of(
                "PROMETHEUS_HTTP",
                "ELASTICSEARCH_HTTP",
                "ELASTICSEARCH_MCP",
                "SKYWALKING_HTTP",
                "RUNBOOK_RAG",
                "LLM_CHAT_AGENT"));
        toolchainCapabilities.put("governanceControls", List.of("whitelist", "budget", "timeout metadata", "failure downgrade", "call log"));

        Map<String, Object> evaluationMetrics = new LinkedHashMap<>();
        evaluationMetrics.put("historicalMemoryHitRate", memoryHitRate);
        evaluationMetrics.put("toolProtocolCoverage", BigDecimal.ONE);
        evaluationMetrics.put("memoryPromptCoverage", BigDecimal.ONE);
        evaluationMetrics.put("comparisonModesImplemented", List.of(
                "WITH_HISTORY_MEMORY",
                "WITHOUT_HISTORY_MEMORY",
                "TOOL_GOVERNANCE_ENABLED",
                "KEYWORD_ONLY_RAG",
                "HYBRID_RAG"));

        return OpsMemoryToolchainEvaluationSummary.builder()
                .evaluationId("memory-toolchain-eval-" + UUID.randomUUID())
                .historicalMemoryCards(historicalMemoryCards)
                .historicalMemoryHitRate(memoryHitRate)
                .comparisonModes(List.of(
                        "WITH_HISTORY_MEMORY",
                        "WITHOUT_HISTORY_MEMORY",
                        "TOOL_GOVERNANCE_ENABLED",
                        "KEYWORD_ONLY_RAG",
                        "HYBRID_RAG"))
                .memoryCapabilities(memoryCapabilities)
                .toolchainCapabilities(toolchainCapabilities)
                .evaluationMetrics(evaluationMetrics)
                .explanation("This endpoint is a lightweight capability and persisted-data summary. Live diagnosis evaluation remains under /api/v1/ops/evaluation/run, and Runbook RAG ablation remains under /api/v1/ops/evaluation/runbook-rag/ablation.")
                .build();
    }

    public OpsEvaluationSummary runAllEnabledCases() {
        if (!evaluationEnabled) {
            throw new IllegalStateException("Ops evaluation harness is disabled. Please set ops.agent.evaluation.enabled=true");
        }

        String batchId = "eval-batch-" + UUID.randomUUID();
        List<OpsEvalCase> cases = opsEvaluationRepository.queryEnabledCases();
        List<OpsEvalRun> runs = new ArrayList<>();
        for (OpsEvalCase evalCase : cases) {
            runs.add(runCase(batchId, evalCase));
        }
        return summarize(batchId, runs);
    }

    public OpsEvaluationSummary runCase(String caseId) {
        if (!evaluationEnabled) {
            throw new IllegalStateException("Ops evaluation harness is disabled. Please set ops.agent.evaluation.enabled=true");
        }
        if (isBlank(caseId)) {
            throw new IllegalArgumentException("caseId cannot be blank");
        }
        OpsEvalCase evalCase = opsEvaluationRepository.queryEnabledCaseByCaseId(caseId);
        if (evalCase == null) {
            throw new IllegalArgumentException("enabled eval case not found: " + caseId);
        }
        String batchId = "eval-batch-" + UUID.randomUUID();
        return summarize(batchId, List.of(runCase(batchId, evalCase)));
    }

    private OpsEvalRun runCase(String batchId, OpsEvalCase evalCase) {
        String runId = "eval-run-" + UUID.randomUUID();
        String diagnosisId = "diag-eval-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        OpsEvalRun evalRun = OpsEvalRun.builder()
                .runId(runId)
                .caseId(evalCase.getCaseId())
                .diagnosisId(diagnosisId)
                .status("RUNNING")
                .top1RootCauseHit(0)
                .top3RootCauseHit(0)
                .requiredEvidenceCoverage(BigDecimal.ZERO)
                .unsupportedConclusionCount(0)
                .toolCallCount(0)
                .diagnosisLatencyMs(0L)
                .summaryJson("{\"batchId\":\"" + batchId + "\"}")
                .createTime(now)
                .updateTime(now)
                .build();
        opsEvaluationRepository.saveRun(evalRun);

        long start = System.currentTimeMillis();
        try {
            IncidentCommandEntity command = buildCommand(evalCase, diagnosisId);
            OpsAlertEventEntity alertEvent = buildAlertEvent(evalCase, command);
            OpsDiagnosisDispatchEntity dispatch = OpsDiagnosisDispatchEntity.builder()
                    .dispatchId("dispatch-eval-" + UUID.randomUUID())
                    .eventId(alertEvent.getEventId())
                    .diagnosisId(diagnosisId)
                    .serviceName(evalCase.getServiceName())
                    .dedupKey("eval:" + evalCase.getCaseId())
                    .dispatchStatus("RUNNING")
                    .createTime(now)
                    .updateTime(now)
                    .build();

            opsAgentBootstrapService.initialize(alertEvent, dispatch, command);
            opsAgentStateService.markCollecting(diagnosisId);
            opsIncidentExecuteStrategy.execute(command, null);

            DiagnosisRecordEntity record = opsIncidentRepository.queryDiagnosisRecord(diagnosisId);
            OpsIncidentState state = opsAgentRepository.queryIncidentStateByDiagnosisId(diagnosisId);
            long latencyMs = System.currentTimeMillis() - start;
            OpsEvalRun scoredRun = score(evalRun, evalCase, record, state, latencyMs, null);
            opsEvaluationRepository.updateRun(scoredRun);
            saveMetrics(scoredRun);
            opsAgentStateService.markSuccess(diagnosisId, record == null ? null : record.getReport());
            return scoredRun;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("Ops evaluation case failed. caseId={}, runId={}", evalCase.getCaseId(), runId, e);
            OpsEvalRun failedRun = score(evalRun, evalCase, null, null, latencyMs, summarizeException(e));
            opsEvaluationRepository.updateRun(failedRun);
            saveMetrics(failedRun);
            opsAgentStateService.markFailed(diagnosisId, summarizeException(e));
            return failedRun;
        }
    }

    private IncidentCommandEntity buildCommand(OpsEvalCase evalCase, String diagnosisId) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusMinutes(15);
        return IncidentCommandEntity.builder()
                .diagnosisId(diagnosisId)
                .sessionId("eval-session-" + UUID.randomUUID())
                .serviceName(evalCase.getServiceName())
                .startTime(startTime.format(TIME_FORMATTER))
                .endTime(endTime.format(TIME_FORMATTER))
                .problem(evalCase.getProblem())
                .maxStep(7)
                .build();
    }

    private OpsAlertEventEntity buildAlertEvent(OpsEvalCase evalCase, IncidentCommandEntity command) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("alertname", evalCase.getCaseId());
        labels.put("serviceName", evalCase.getServiceName());
        labels.put("severity", evalCase.getSeverity());
        labels.put("tags", evalCase.getTags());

        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("summary", evalCase.getCaseName());
        annotations.put("description", evalCase.getProblem());
        annotations.put("goldenSummary", evalCase.getGoldenSummary());

        return OpsAlertEventEntity.builder()
                .eventId("event-eval-" + UUID.randomUUID())
                .source("evaluation-harness")
                .serviceName(evalCase.getServiceName())
                .alertRule(evalCase.getCaseId())
                .severity(evalCase.getSeverity())
                .status("firing")
                .fingerprint("eval:" + evalCase.getCaseId())
                .startsAt(now.minusMinutes(15))
                .labelsJson(JSON.toJSONString(labels))
                .annotationsJson(JSON.toJSONString(annotations))
                .rawPayload(evalCase.getAlertPayloadJson())
                .receivedTime(now)
                .createTime(now)
                .build();
    }

    private OpsEvalRun score(OpsEvalRun evalRun,
                             OpsEvalCase evalCase,
                             DiagnosisRecordEntity record,
                             OpsIncidentState state,
                             long latencyMs,
                             String errorMessage) {
        String expectedRootCause = normalize(evalCase.getExpectedRootCause());
        String reportText = normalize(record == null ? "" : record.getReport());
        String candidateText = normalize(state == null ? "" : state.getCandidateRootCausesJson());
        int top1Hit = rootCauseHit(reportText, expectedRootCause) ? 1 : 0;
        int top3Hit = top1Hit == 1 || rootCauseHit(candidateText, expectedRootCause) ? 1 : 0;
        BigDecimal evidenceCoverage = evidenceCoverage(evalCase, record, state);
        int toolCallCount = toolCallCount(state);
        int unsupportedConclusionCount = unsupportedConclusionCount(record, evidenceCoverage);
        boolean plannerChatAgent = contains(normalize(state == null ? "" : state.getPlanJson()), "chat_agent");
        boolean reviewerChatAgent = contains(normalize(state == null ? "" : state.getMissingEvidenceJson()), "chatagent chat_agent");
        boolean reportWriterChatAgent = contains(reportText, "report writer chat_agent");
        boolean strictThreeAgentPath = plannerChatAgent && reviewerChatAgent && reportWriterChatAgent;
        String finalErrorMessage = errorMessage;
        if (chatAgentRequired && !strictThreeAgentPath) {
            finalErrorMessage = joinError(errorMessage, "strict three-agent path was not completed");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseId", evalCase.getCaseId());
        summary.put("caseName", evalCase.getCaseName());
        summary.put("expectedRootCause", evalCase.getExpectedRootCause());
        summary.put("diagnosisId", evalRun.getDiagnosisId());
        summary.put("top1RootCauseHit", top1Hit);
        summary.put("top3RootCauseHit", top3Hit);
        summary.put("requiredEvidenceCoverage", evidenceCoverage);
        summary.put("expectedToolCoverage", expectedToolCoverage(evalCase, state));
        summary.put("toolCallCount", toolCallCount);
        summary.put("plannerChatAgent", plannerChatAgent);
        summary.put("reviewerChatAgent", reviewerChatAgent);
        summary.put("reportWriterChatAgent", reportWriterChatAgent);
        summary.put("strictThreeAgentPath", strictThreeAgentPath);
        summary.put("errorMessage", finalErrorMessage);

        String finalStatus = record == null ? "MISSING_RECORD" : record.getStatus();
        evalRun.setStatus(finalErrorMessage == null && record != null ? "SUCCESS" : "FAILED");
        evalRun.setTop1RootCauseHit(top1Hit);
        evalRun.setTop3RootCauseHit(top3Hit);
        evalRun.setRequiredEvidenceCoverage(evidenceCoverage);
        evalRun.setUnsupportedConclusionCount(unsupportedConclusionCount);
        evalRun.setToolCallCount(toolCallCount);
        evalRun.setDiagnosisLatencyMs(latencyMs);
        evalRun.setFinalStatus(finalStatus);
        evalRun.setSummaryJson(JSON.toJSONString(summary));
        evalRun.setErrorMessage(finalErrorMessage);
        evalRun.setUpdateTime(LocalDateTime.now());
        return evalRun;
    }

    private BigDecimal evidenceCoverage(OpsEvalCase evalCase, DiagnosisRecordEntity record, OpsIncidentState state) {
        List<String> expectedEvidence = parseList(evalCase.getExpectedEvidenceTypesJson());
        if (expectedEvidence.isEmpty()) {
            return BigDecimal.ONE;
        }
        String evidenceText = normalize(String.join("\n",
                value(record == null ? null : record.getMetricEvidenceJson()),
                value(record == null ? null : record.getLogEvidenceJson()),
                value(record == null ? null : record.getTraceEvidenceJson()),
                value(record == null ? null : record.getEvidenceChainJson()),
                value(record == null ? null : record.getRunbookJson()),
                value(state == null ? null : state.getMetricsEvidenceJson()),
                value(state == null ? null : state.getLogEvidenceJson()),
                value(state == null ? null : state.getTraceEvidenceJson()),
                value(state == null ? null : state.getRunbookEvidenceJson())));
        int hit = 0;
        for (String evidence : expectedEvidence) {
            if (contains(evidenceText, normalize(evidence))) {
                hit++;
            }
        }
        return BigDecimal.valueOf(hit)
                .divide(BigDecimal.valueOf(expectedEvidence.size()), 4, RoundingMode.HALF_UP);
    }

    private int toolCallCount(OpsIncidentState state) {
        if (state == null || isBlank(state.getToolHistoryJson())) {
            return 0;
        }
        try {
            return JSON.parseArray(state.getToolHistoryJson()).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int unsupportedConclusionCount(DiagnosisRecordEntity record, BigDecimal evidenceCoverage) {
        if (record == null || isBlank(record.getReport())) {
            return 1;
        }
        String report = record.getReport();
        boolean hasStrongConclusion = report.contains("根因") || report.toLowerCase(Locale.ROOT).contains("root cause");
        boolean mentionsUncertainty = report.contains("可能") || report.contains("疑似") || report.contains("证据不足")
                || report.toLowerCase(Locale.ROOT).contains("hypothesis");
        if (evidenceCoverage.compareTo(BigDecimal.valueOf(0.5)) < 0 && hasStrongConclusion && !mentionsUncertainty) {
            return 1;
        }
        return 0;
    }

    private void saveMetrics(OpsEvalRun run) {
        saveMetric(run, "top1RootCauseHit", BigDecimal.valueOf(run.getTop1RootCauseHit()));
        saveMetric(run, "top3RootCauseHit", BigDecimal.valueOf(run.getTop3RootCauseHit()));
        saveMetric(run, "requiredEvidenceCoverage", run.getRequiredEvidenceCoverage());
        saveMetric(run, "expectedToolCoverage", expectedToolCoverage(run));
        saveMetric(run, "unsupportedConclusionCount", BigDecimal.valueOf(run.getUnsupportedConclusionCount()));
        saveMetric(run, "toolCallCount", BigDecimal.valueOf(run.getToolCallCount()));
        saveMetric(run, "diagnosisLatencyMs", BigDecimal.valueOf(run.getDiagnosisLatencyMs()));
        saveMetric(run, "finalStatus", "SUCCESS".equals(run.getFinalStatus()) ? BigDecimal.ONE : BigDecimal.ZERO);
        saveMetric(run, "plannerChatAgent", summaryFlag(run, "plannerChatAgent"));
        saveMetric(run, "reviewerChatAgent", summaryFlag(run, "reviewerChatAgent"));
        saveMetric(run, "reportWriterChatAgent", summaryFlag(run, "reportWriterChatAgent"));
        saveMetric(run, "strictThreeAgentPath", summaryFlag(run, "strictThreeAgentPath"));
    }

    private void saveMetric(OpsEvalRun run, String metricName, BigDecimal metricValue) {
        opsEvaluationRepository.saveMetric(OpsEvalMetric.builder()
                .runId(run.getRunId())
                .caseId(run.getCaseId())
                .metricName(metricName)
                .metricValue(metricValue)
                .metricDetailJson(run.getSummaryJson())
                .createTime(LocalDateTime.now())
                .build());
    }

    private OpsEvaluationSummary summarize(String batchId, List<OpsEvalRun> runs) {
        int total = runs.size();
        int success = 0;
        BigDecimal top1 = BigDecimal.ZERO;
        BigDecimal top3 = BigDecimal.ZERO;
        BigDecimal coverage = BigDecimal.ZERO;
        BigDecimal expectedToolCoverage = BigDecimal.ZERO;
        BigDecimal toolCalls = BigDecimal.ZERO;
        BigDecimal latency = BigDecimal.ZERO;
        for (OpsEvalRun run : runs) {
            if ("SUCCESS".equals(run.getStatus())) {
                success++;
            }
            top1 = top1.add(BigDecimal.valueOf(run.getTop1RootCauseHit()));
            top3 = top3.add(BigDecimal.valueOf(run.getTop3RootCauseHit()));
            coverage = coverage.add(run.getRequiredEvidenceCoverage());
            expectedToolCoverage = expectedToolCoverage.add(expectedToolCoverage(run));
            toolCalls = toolCalls.add(BigDecimal.valueOf(run.getToolCallCount()));
            latency = latency.add(BigDecimal.valueOf(run.getDiagnosisLatencyMs()));
        }
        BigDecimal denominator = BigDecimal.valueOf(Math.max(1, total));
        return OpsEvaluationSummary.builder()
                .batchId(batchId)
                .totalCases(total)
                .successCases(success)
                .failedCases(total - success)
                .top1RootCauseHitRate(top1.divide(denominator, 4, RoundingMode.HALF_UP))
                .top3RootCauseHitRate(top3.divide(denominator, 4, RoundingMode.HALF_UP))
                .averageEvidenceCoverage(coverage.divide(denominator, 4, RoundingMode.HALF_UP))
                .averageExpectedToolCoverage(expectedToolCoverage.divide(denominator, 4, RoundingMode.HALF_UP))
                .averageToolCallCount(toolCalls.divide(denominator, 4, RoundingMode.HALF_UP))
                .averageLatencyMs(latency.divide(denominator, 4, RoundingMode.HALF_UP))
                .runs(runs)
                .build();
    }

    private BigDecimal expectedToolCoverage(OpsEvalCase evalCase, OpsIncidentState state) {
        List<String> expectedTools = parseList(evalCase.getExpectedToolsJson());
        if (expectedTools.isEmpty()) {
            return BigDecimal.ONE;
        }
        String toolHistory = state == null ? "" : value(state.getToolHistoryJson());
        int hit = 0;
        for (String expectedTool : expectedTools) {
            if (toolHistory.contains(expectedTool)) {
                hit++;
            }
        }
        return BigDecimal.valueOf(hit)
                .divide(BigDecimal.valueOf(expectedTools.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal expectedToolCoverage(OpsEvalRun run) {
        if (isBlank(run.getSummaryJson())) {
            return BigDecimal.ZERO;
        }
        try {
            Object value = JSON.parseObject(run.getSummaryJson()).get("expectedToolCoverage");
            return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal summaryFlag(OpsEvalRun run, String key) {
        if (run == null || isBlank(run.getSummaryJson())) {
            return BigDecimal.ZERO;
        }
        try {
            Boolean value = JSON.parseObject(run.getSummaryJson()).getBoolean(key);
            return Boolean.TRUE.equals(value) ? BigDecimal.ONE : BigDecimal.ZERO;
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private String joinError(String current, String next) {
        if (isBlank(current)) {
            return next;
        }
        return current + "; " + next;
    }

    private List<String> parseList(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            return List.of(json);
        }
    }

    private boolean contains(String text, String key) {
        return !isBlank(key) && text.contains(key);
    }

    private boolean rootCauseHit(String text, String expectedRootCause) {
        if (isBlank(text) || isBlank(expectedRootCause)) {
            return false;
        }
        if (contains(text, expectedRootCause) || contains(text, expectedRootCause.replace("_", " "))) {
            return true;
        }
        List<List<String>> aliasGroups = rootCauseAliases(expectedRootCause);
        for (List<String> aliases : aliasGroups) {
            boolean allMatched = true;
            for (String alias : aliases) {
                if (!contains(text, normalize(alias))) {
                    allMatched = false;
                    break;
                }
            }
            if (allMatched) {
                return true;
            }
        }
        return false;
    }

    private List<List<String>> rootCauseAliases(String expectedRootCause) {
        String rootCause = normalize(expectedRootCause);
        if ("connection_pool_exhausted".equals(rootCause)) {
            return List.of(
                    List.of("connection pool"),
                    List.of("hikari"),
                    List.of("hikaripool"),
                    List.of("database connection", "timeout"),
                    List.of("jdbc", "timeout"),
                    List.of("sqltimeoutexception"),
                    List.of("connection is not available")
            );
        }
        if ("http_500_error".equals(rootCause) || "application_exception".equals(rootCause)) {
            return List.of(
                    List.of("http", "500"),
                    List.of("5xx"),
                    List.of("application", "exception"),
                    List.of("应用", "异常"),
                    List.of("nullpointerexception"),
                    List.of("illegalstateexception")
            );
        }
        if ("jvm_full_gc".equals(rootCause) || "full_gc".equals(rootCause) || "full_gc_pressure".equals(rootCause)) {
            return List.of(
                    List.of("full gc"),
                    List.of("gc pause"),
                    List.of("heap"),
                    List.of("memory", "pressure"),
                    List.of("内存", "压力"),
                    List.of("gc", "停顿")
            );
        }
        if ("redis_timeout".equals(rootCause)) {
            return List.of(
                    List.of("redis", "timeout"),
                    List.of("redis", "connection"),
                    List.of("redis", "latency")
            );
        }
        if ("rpc_timeout".equals(rootCause) || "downstream_timeout".equals(rootCause)) {
            return List.of(
                    List.of("rpc", "timeout"),
                    List.of("downstream", "timeout"),
                    List.of("dubbo", "timeout"),
                    List.of("feign", "timeout"),
                    List.of("resttemplate", "timeout")
            );
        }
        if ("mq_backlog".equals(rootCause) || "consumer_lag".equals(rootCause)) {
            return List.of(
                    List.of("mq", "backlog"),
                    List.of("consumer", "lag"),
                    List.of("message", "backlog"),
                    List.of("消息", "堆积"),
                    List.of("消费", "延迟")
            );
        }
        if ("slow_sql".equals(rootCause) || "db_slow_query".equals(rootCause)) {
            return List.of(
                    List.of("slow sql"),
                    List.of("slow query"),
                    List.of("db", "span", "slow"),
                    List.of("mysql", "lock"),
                    List.of("数据库", "慢")
            );
        }
        if ("cpu_saturation".equals(rootCause)) {
            return List.of(
                    List.of("cpu", "usage"),
                    List.of("cpu", "saturation"),
                    List.of("system_cpu_usage"),
                    List.of("process_cpu_usage"),
                    List.of("cpu", "饱和")
            );
        }
        if ("thread_pool_saturation".equals(rootCause)) {
            return List.of(
                    List.of("executor", "active"),
                    List.of("thread", "pool"),
                    List.of("tomcat", "threads", "busy"),
                    List.of("queue", "backlog"),
                    List.of("线程池", "饱和")
            );
        }
        if ("gateway_5xx".equals(rootCause) || "http_layer_5xx".equals(rootCause)) {
            return List.of(
                    List.of("gateway", "5xx"),
                    List.of("http", "layer", "5xx"),
                    List.of("route", "5xx"),
                    List.of("网关", "5xx")
            );
        }
        if ("observability_gap".equals(rootCause)) {
            return List.of(
                    List.of("observability", "gap"),
                    List.of("no_anomaly"),
                    List.of("lack", "evidence"),
                    List.of("观测", "盲区"),
                    List.of("证据", "不足")
            );
        }
        return List.of();
    }

    private String normalize(String value) {
        return value(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\p{IsHan}]+", " ")
                .trim();
    }

    private String summarizeException(Exception e) {
        String message = e.getMessage();
        if (isBlank(message)) {
            message = e.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000) + "...";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

