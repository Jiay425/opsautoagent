package com.opsautoagent.domain.ops.agent.eval;

import com.opsautoagent.domain.ops.adapter.gateway.IOpsRunbookGateway;
import com.opsautoagent.domain.ops.adapter.repository.IOpsEvaluationRepository;
import com.opsautoagent.domain.ops.model.entity.EvidenceSignalEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
public class OpsRunbookRagEvaluationService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${ops.agent.evaluation.enabled:false}")
    private boolean evaluationEnabled;

    @Resource
    private IOpsRunbookGateway opsRunbookGateway;

    @Resource(name = "fileOpsRunbookGateway")
    private IOpsRunbookGateway keywordOnlyRunbookGateway;

    @Resource
    private IOpsEvaluationRepository opsEvaluationRepository;

    public OpsRunbookRagEvaluationSummary runRecallEvaluation() {
        return runRecallEvaluation("HYBRID_RAG", opsRunbookGateway);
    }

    public OpsRunbookRagEvaluationSummary runAblationEvaluation() {
        if (!evaluationEnabled) {
            throw new IllegalStateException("Ops evaluation harness is disabled. Please set ops.agent.evaluation.enabled=true");
        }

        OpsRunbookRagEvaluationSummary hybridSummary = runRecallEvaluation("HYBRID_RAG", opsRunbookGateway);
        OpsRunbookRagEvaluationSummary keywordSummary = runRecallEvaluation("KEYWORD_ONLY", keywordOnlyRunbookGateway);
        Map<String, OpsRunbookRagEvaluationSummary> ablationSummaries = new LinkedHashMap<>();
        ablationSummaries.put("HYBRID_RAG", summaryView(hybridSummary));
        ablationSummaries.put("KEYWORD_ONLY", summaryView(keywordSummary));
        hybridSummary.setAblationSummaries(ablationSummaries);
        return hybridSummary;
    }

    private OpsRunbookRagEvaluationSummary runRecallEvaluation(String mode, IOpsRunbookGateway runbookGateway) {
        if (!evaluationEnabled) {
            throw new IllegalStateException("Ops evaluation harness is disabled. Please set ops.agent.evaluation.enabled=true");
        }

        String batchId = mode.toLowerCase(Locale.ROOT) + "-rag-eval-batch-" + UUID.randomUUID();
        List<OpsRunbookRagEvalRun> runs = new ArrayList<>();
        for (OpsRunbookRagEvalCase evalCase : cases()) {
            OpsRunbookRagEvalRun run = runCase(batchId, mode, evalCase, runbookGateway, 5);
            runs.add(run);
            saveMetrics(batchId, mode, run);
        }
        OpsRunbookRagEvaluationSummary summary = summarize(batchId, mode, runs);
        writeReportArtifacts(summary);
        return summary;
    }

    private OpsRunbookRagEvalRun runCase(String batchId,
                                         String mode,
                                         OpsRunbookRagEvalCase evalCase,
                                         IOpsRunbookGateway runbookGateway,
                                         int topK) {
        String runId = "rag-eval-run-" + UUID.randomUUID();
        long start = System.currentTimeMillis();
        try {
            IncidentCommandEntity command = buildCommand(evalCase);
            List<RunbookMatchEntity> matches = runbookGateway.searchByEvidenceSignals(command, evalCase.getEvidenceSignals(), topK);
            long latencyMs = System.currentTimeMillis() - start;
            int rank = firstExpectedRank(evalCase.getExpectedRunbookIds(), matches);
            int top1Hit = rank == 1 ? 1 : 0;
            int top3Hit = rank > 0 && rank <= 3 ? 1 : 0;
            int top5Hit = rank > 0 && rank <= 5 ? 1 : 0;
            BigDecimal reciprocalRank = rank > 0
                    ? BigDecimal.ONE.divide(BigDecimal.valueOf(rank), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return OpsRunbookRagEvalRun.builder()
                    .runId(runId)
                    .caseId(evalCase.getCaseId())
                    .caseName(evalCase.getCaseName())
                    .status(top3Hit == 1 ? "SUCCESS" : "FAILED")
                    .expectedRunbookIds(evalCase.getExpectedRunbookIds())
                    .retrievedRunbooks(matches)
                    .top1Hit(top1Hit)
                    .top3Hit(top3Hit)
                    .top5Hit(top5Hit)
                    .rank(rank)
                    .reciprocalRank(reciprocalRank)
                    .latencyMs(latencyMs)
                    .failureReason(top3Hit == 1 ? "" : failureReason(evalCase, matches, rank))
                    .build();
        } catch (Exception e) {
            log.warn("Runbook RAG evaluation case failed. batchId={}, mode={}, caseId={}", batchId, mode, evalCase.getCaseId(), e);
            return OpsRunbookRagEvalRun.builder()
                    .runId(runId)
                    .caseId(evalCase.getCaseId())
                    .caseName(evalCase.getCaseName())
                    .status("FAILED")
                    .expectedRunbookIds(evalCase.getExpectedRunbookIds())
                    .retrievedRunbooks(List.of())
                    .top1Hit(0)
                    .top3Hit(0)
                    .top5Hit(0)
                    .rank(0)
                    .reciprocalRank(BigDecimal.ZERO)
                    .latencyMs(System.currentTimeMillis() - start)
                    .failureReason("exception during retrieval")
                    .errorMessage(summarizeException(e))
                    .build();
        }
    }

    private IncidentCommandEntity buildCommand(OpsRunbookRagEvalCase evalCase) {
        LocalDateTime endTime = LocalDateTime.now();
        return IncidentCommandEntity.builder()
                .diagnosisId("rag-eval-diag-" + UUID.randomUUID())
                .sessionId("rag-eval-session-" + UUID.randomUUID())
                .serviceName(evalCase.getServiceName())
                .problem(evalCase.getProblem())
                .startTime(endTime.minusMinutes(15).format(TIME_FORMATTER))
                .endTime(endTime.format(TIME_FORMATTER))
                .maxStep(1)
                .build();
    }

    private int firstExpectedRank(List<String> expectedRunbookIds, List<RunbookMatchEntity> matches) {
        if (expectedRunbookIds == null || expectedRunbookIds.isEmpty() || matches == null || matches.isEmpty()) {
            return 0;
        }
        List<String> expected = expectedRunbookIds.stream().map(this::normalizeRunbookId).toList();
        for (int i = 0; i < matches.size(); i++) {
            RunbookMatchEntity match = matches.get(i);
            String matchId = normalizeRunbookId(match.getRunbookId());
            String path = normalizeRunbookId(match.getPath());
            for (String expectedId : expected) {
                if (matchId.equals(expectedId) || path.contains(expectedId)) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    private void saveMetrics(String batchId, String mode, OpsRunbookRagEvalRun run) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batchId", batchId);
        detail.put("mode", mode);
        detail.put("caseId", run.getCaseId());
        detail.put("caseName", run.getCaseName());
        detail.put("expectedRunbookIds", run.getExpectedRunbookIds());
        detail.put("retrievedRunbooks", run.getRetrievedRunbooks());
        detail.put("rank", run.getRank());
        detail.put("errorMessage", run.getErrorMessage());
        String detailJson = JSON.toJSONString(detail);
        saveMetric(run, mode + ".ragTop1Recall", BigDecimal.valueOf(run.getTop1Hit()), detailJson);
        saveMetric(run, mode + ".ragTop3Recall", BigDecimal.valueOf(run.getTop3Hit()), detailJson);
        saveMetric(run, mode + ".ragTop5Recall", BigDecimal.valueOf(run.getTop5Hit()), detailJson);
        saveMetric(run, mode + ".ragReciprocalRank", run.getReciprocalRank(), detailJson);
        saveMetric(run, mode + ".ragLatencyMs", BigDecimal.valueOf(run.getLatencyMs()), detailJson);
    }

    private void saveMetric(OpsRunbookRagEvalRun run, String metricName, BigDecimal value, String detailJson) {
        opsEvaluationRepository.saveMetric(OpsEvalMetric.builder()
                .runId(run.getRunId())
                .caseId(run.getCaseId())
                .metricName(metricName)
                .metricValue(value)
                .metricDetailJson(detailJson)
                .createTime(LocalDateTime.now())
                .build());
    }

    private OpsRunbookRagEvaluationSummary summarize(String batchId, String mode, List<OpsRunbookRagEvalRun> runs) {
        int total = runs.size();
        int success = 0;
        BigDecimal top1 = BigDecimal.ZERO;
        BigDecimal top3 = BigDecimal.ZERO;
        BigDecimal top5 = BigDecimal.ZERO;
        BigDecimal mrr = BigDecimal.ZERO;
        BigDecimal latency = BigDecimal.ZERO;
        for (OpsRunbookRagEvalRun run : runs) {
            if ("SUCCESS".equals(run.getStatus())) {
                success++;
            }
            top1 = top1.add(BigDecimal.valueOf(run.getTop1Hit()));
            top3 = top3.add(BigDecimal.valueOf(run.getTop3Hit()));
            top5 = top5.add(BigDecimal.valueOf(run.getTop5Hit()));
            mrr = mrr.add(run.getReciprocalRank());
            latency = latency.add(BigDecimal.valueOf(run.getLatencyMs()));
        }
        BigDecimal denominator = BigDecimal.valueOf(Math.max(1, total));
        return OpsRunbookRagEvaluationSummary.builder()
                .batchId(batchId)
                .mode(mode)
                .totalCases(total)
                .successCases(success)
                .failedCases(total - success)
                .top1Recall(top1.divide(denominator, 4, RoundingMode.HALF_UP))
                .top3Recall(top3.divide(denominator, 4, RoundingMode.HALF_UP))
                .top5Recall(top5.divide(denominator, 4, RoundingMode.HALF_UP))
                .meanReciprocalRank(mrr.divide(denominator, 4, RoundingMode.HALF_UP))
                .averageLatencyMs(latency.divide(denominator, 4, RoundingMode.HALF_UP))
                .rootCauseHitRate(top3.divide(denominator, 4, RoundingMode.HALF_UP))
                .runs(runs)
                .build();
    }

    private OpsRunbookRagEvaluationSummary summaryView(OpsRunbookRagEvaluationSummary summary) {
        return OpsRunbookRagEvaluationSummary.builder()
                .batchId(summary.getBatchId())
                .mode(summary.getMode())
                .totalCases(summary.getTotalCases())
                .successCases(summary.getSuccessCases())
                .failedCases(summary.getFailedCases())
                .top1Recall(summary.getTop1Recall())
                .top3Recall(summary.getTop3Recall())
                .top5Recall(summary.getTop5Recall())
                .meanReciprocalRank(summary.getMeanReciprocalRank())
                .averageLatencyMs(summary.getAverageLatencyMs())
                .rootCauseHitRate(summary.getRootCauseHitRate())
                .reportJsonPath(summary.getReportJsonPath())
                .reportMarkdownPath(summary.getReportMarkdownPath())
                .failureCasesPath(summary.getFailureCasesPath())
                .build();
    }

    private String failureReason(OpsRunbookRagEvalCase evalCase, List<RunbookMatchEntity> matches, int rank) {
        if (matches == null || matches.isEmpty()) {
            return "no runbook returned; expected=" + evalCase.getExpectedRunbookIds();
        }
        if (rank == 0) {
            return "expected runbook not retrieved in topK; expected=" + evalCase.getExpectedRunbookIds()
                    + ", retrieved=" + matches.stream().map(RunbookMatchEntity::getRunbookId).toList();
        }
        return "expected runbook rank=" + rank + " is outside Top3; expected=" + evalCase.getExpectedRunbookIds();
    }

    private void writeReportArtifacts(OpsRunbookRagEvaluationSummary summary) {
        try {
            Path dir = Path.of("data", "runbook-rag-eval", summary.getBatchId());
            Files.createDirectories(dir);
            Path jsonPath = dir.resolve("report.json");
            Path markdownPath = dir.resolve("report.md");
            Path failuresPath = dir.resolve("failures.json");
            List<OpsRunbookRagEvalRun> failures = summary.getRuns() == null ? List.of()
                    : summary.getRuns().stream().filter(run -> !"SUCCESS".equals(run.getStatus())).toList();
            summary.setReportJsonPath(jsonPath.toString());
            summary.setReportMarkdownPath(markdownPath.toString());
            summary.setFailureCasesPath(failuresPath.toString());
            Files.writeString(jsonPath, JSON.toJSONString(summary, true), StandardCharsets.UTF_8);
            Files.writeString(markdownPath, markdown(summary, failures), StandardCharsets.UTF_8);
            Files.writeString(failuresPath, JSON.toJSONString(failures, true), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Write Runbook RAG eval report failed. batchId={}", summary == null ? "" : summary.getBatchId(), e);
        }
    }

    private String markdown(OpsRunbookRagEvaluationSummary summary, List<OpsRunbookRagEvalRun> failures) {
        StringBuilder md = new StringBuilder();
        md.append("# Runbook RAG Evaluation Report\n\n");
        md.append("- batchId: ").append(summary.getBatchId()).append("\n");
        md.append("- mode: ").append(summary.getMode()).append("\n");
        md.append("- totalCases: ").append(summary.getTotalCases()).append("\n");
        md.append("- successCases: ").append(summary.getSuccessCases()).append("\n");
        md.append("- failedCases: ").append(summary.getFailedCases()).append("\n");
        md.append("- Top1 Recall: ").append(summary.getTop1Recall()).append("\n");
        md.append("- Top3 Recall: ").append(summary.getTop3Recall()).append("\n");
        md.append("- Top5 Recall: ").append(summary.getTop5Recall()).append("\n");
        md.append("- MRR: ").append(summary.getMeanReciprocalRank()).append("\n");
        md.append("- rootCauseHitRate: ").append(summary.getRootCauseHitRate()).append("\n");
        md.append("- averageLatencyMs: ").append(summary.getAverageLatencyMs()).append("\n\n");
        md.append("## Cases\n\n");
        md.append("| caseId | status | rank | expected | topRetrieved | reason |\n");
        md.append("|---|---:|---:|---|---|---|\n");
        if (summary.getRuns() != null) {
            for (OpsRunbookRagEvalRun run : summary.getRuns()) {
                md.append("| ").append(run.getCaseId())
                        .append(" | ").append(run.getStatus())
                        .append(" | ").append(run.getRank())
                        .append(" | ").append(run.getExpectedRunbookIds())
                        .append(" | ").append(topRetrieved(run))
                        .append(" | ").append(escapeMarkdown(value(run.getFailureReason())))
                        .append(" |\n");
            }
        }
        md.append("\n## Failure Samples\n\n");
        if (failures == null || failures.isEmpty()) {
            md.append("No failed samples.\n");
        } else {
            for (OpsRunbookRagEvalRun failure : failures) {
                md.append("- ").append(failure.getCaseId()).append(": ")
                        .append(value(failure.getFailureReason())).append("\n");
            }
        }
        return md.toString();
    }

    private String topRetrieved(OpsRunbookRagEvalRun run) {
        if (run.getRetrievedRunbooks() == null || run.getRetrievedRunbooks().isEmpty()) {
            return "";
        }
        return run.getRetrievedRunbooks().stream()
                .limit(3)
                .map(match -> value(match.getRunbookId()) + "(" + value(match.getRetrievalMode()) + ","
                        + value(match.getHybridScore()) + ")")
                .toList()
                .toString();
    }

    private String escapeMarkdown(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<OpsRunbookRagEvalCase> cases() {
        return List.of(
                evalCase("rag-db-pool-hikari", "Hikari pool saturation",
                        "checkout 5xx and high database connection wait, Hikari active connections close to max",
                        List.of("database-connection-pool"),
                        signal("prometheus", "metric", "hikari_connections_usage_percent", "ANOMALY", "Hikari active/max ratio 95%", "hikari_connections_usage_percent max=95"),
                        signal("elk", "log", "SQLTransientConnectionException", "ANOMALY", "Connection is not available from HikariPool", "SQLTransientConnectionException HikariPool Connection is not available")),
                evalCase("rag-db-pool-pending", "DB pending requests",
                        "payment API latency, Hikari pending requests increase and connection timeout total rises",
                        List.of("database-connection-pool"),
                        signal("prometheus", "metric", "hikari_connections_pending", "ANOMALY", "pending database connection requests", "hikari_connections_pending max=18"),
                        signal("prometheus", "metric", "hikari_connection_timeout_total", "ANOMALY", "database connection timeout counter increased", "hikari_connection_timeout_total increased")),
                evalCase("rag-http-500-npe", "Application exception 500",
                        "order creation endpoint returns HTTP 500 with Java stack trace",
                        List.of("http-500-error"),
                        signal("prometheus", "metric", "http_5xx_rate_percent", "ANOMALY", "5xx rate increased", "http_5xx_rate_percent max=14"),
                        signal("elk", "log", "NullPointerException", "ANOMALY", "NPE in OrderService.createOrder", "NullPointerException at OrderService.createOrder")),
                evalCase("rag-http-500-illegal-state", "Application IllegalStateException",
                        "create order endpoint has 500 response and IllegalStateException in service layer",
                        List.of("http-500-error"),
                        signal("elk", "log", "IllegalStateException", "ANOMALY", "IllegalStateException thrown during checkout", "IllegalStateException: order status invalid"),
                        signal("skywalking", "trace", "error_trace", "ANOMALY", "trace endpoint /api/order/create failed", "HTTP 500 error trace")),
                evalCase("rag-gateway-502", "Gateway HTTP 5xx",
                        "external users see 502 and 503, application logs and traces have no matching exception",
                        List.of("gateway-http-5xx"),
                        signal("prometheus", "metric", "http_5xx_rate_percent", "ANOMALY", "5xx spike visible at edge", "502 503 spike"),
                        signal("elk", "log", "application_error", "NO_ANOMALY", "no application stack trace", "NO_ANOMALY")),
                evalCase("rag-rpc-dubbo-timeout", "Dubbo timeout",
                        "submit order p95 latency high and downstream Dubbo provider timeout",
                        List.of("rpc-timeout"),
                        signal("elk", "log", "RpcException", "ANOMALY", "Dubbo remote method timeout", "RpcException Invoke remote method timeout"),
                        signal("skywalking", "trace", "dubbo_provider_span", "ANOMALY", "downstream dubbo provider span 2800ms", "dubbo span duration=2800ms")),
                evalCase("rag-rpc-feign-read-timeout", "Feign downstream timeout",
                        "coupon service call read timed out and downstream HTTP span failed",
                        List.of("rpc-timeout"),
                        signal("elk", "log", "FeignException", "ANOMALY", "Feign client read timed out", "ResourceAccessException read timed out Feign"),
                        signal("skywalking", "trace", "downstream_http_span", "ANOMALY", "downstream HTTP call timed out", "HTTP client span timeout")),
                evalCase("rag-redis-command-timeout", "Redis command timeout",
                        "checkout degradation with RedisCommandTimeoutException and cache span timeout",
                        List.of("redis-timeout"),
                        signal("elk", "log", "RedisCommandTimeoutException", "ANOMALY", "Redis command timed out", "RedisCommandTimeoutException Lettuce command timed out"),
                        signal("skywalking", "trace", "redis_get", "ANOMALY", "Redis GET span timeout", "Redis GET timeout")),
                evalCase("rag-redis-connection-pool", "Redis pool pressure",
                        "cache access latency increased because Redis connection pool exhausted",
                        List.of("redis-timeout"),
                        signal("elk", "log", "redis_pool_exhausted", "ANOMALY", "Redis connection pool exhausted", "Redis connection pool exhausted command timeout"),
                        signal("skywalking", "trace", "redis_dependency", "ANOMALY", "redis dependency latency high", "redis dependency span slow")),
                evalCase("rag-jvm-full-gc", "JVM Full GC",
                        "p99 latency spikes, heap usage high, long Full GC pauses",
                        List.of("jvm-full-gc"),
                        signal("prometheus", "metric", "jvm_gc_pause_seconds", "ANOMALY", "GC pause max high", "jvm_gc_pause_seconds max=3.2"),
                        signal("elk", "log", "Full GC", "ANOMALY", "Full GC messages", "Full GC allocation failure")),
                evalCase("rag-jvm-oom", "JVM OOM risk",
                        "heap memory keeps rising and repeated GC overhead warning appears",
                        List.of("jvm-full-gc"),
                        signal("prometheus", "metric", "jvm_memory_used_bytes", "ANOMALY", "heap memory pressure", "jvm_memory_used_bytes ratio=0.92"),
                        signal("elk", "log", "OutOfMemoryError", "ANOMALY", "OOM risk in logs", "OutOfMemoryError Java heap space")),
                evalCase("rag-mq-consumer-lag", "MQ consumer lag",
                        "order fulfillment delayed, message backlog and consumer lag are increasing",
                        List.of("mq-backlog"),
                        signal("prometheus", "metric", "mq_consumer_lag", "ANOMALY", "consumer lag increased", "mq_consumer_lag max=12000"),
                        signal("elk", "log", "consume_timeout", "ANOMALY", "consumer batch timeout", "consume batch timeout retry")),
                evalCase("rag-mq-retry-storm", "MQ retry storm",
                        "delayed task queue grows with repeated message retry and dead letter warning",
                        List.of("mq-backlog"),
                        signal("elk", "log", "message_retry", "ANOMALY", "message retry storm", "message retry dead-letter backlog"),
                        signal("prometheus", "metric", "mq_backlog", "ANOMALY", "queue backlog increased", "mq_backlog increasing")),
                evalCase("rag-slow-sql-select", "Slow SQL select",
                        "product query slow, DB span SELECT product_sku takes 2400ms",
                        List.of("slow-sql-db-span"),
                        signal("skywalking", "trace", "db_span", "ANOMALY", "SELECT product_sku slow db span", "SELECT product_sku duration=2400ms"),
                        signal("elk", "log", "slow_query", "ANOMALY", "slow query log", "slow query SELECT product_sku")),
                evalCase("rag-slow-sql-lock", "MySQL lock wait",
                        "inventory update timeout with lock wait exceeded and slow DB update span",
                        List.of("slow-sql-db-span"),
                        signal("elk", "log", "Lock wait timeout", "ANOMALY", "MySQL lock wait timeout", "Lock wait timeout exceeded"),
                        signal("skywalking", "trace", "db_update_span", "ANOMALY", "update inventory db span slow", "UPDATE inventory duration=3100ms")),
                evalCase("rag-cpu-saturation", "CPU saturation",
                        "all endpoints slower, process CPU and system CPU high",
                        List.of("cpu-saturation"),
                        signal("prometheus", "metric", "process_cpu_usage", "ANOMALY", "process cpu usage high", "process_cpu_usage max=0.96"),
                        signal("elk", "log", "hot_loop", "ANOMALY", "hot loop in pricing calculation", "hot loop pricing calculation")),
                evalCase("rag-thread-pool-rejected", "Thread pool rejected",
                        "async settlement tasks delayed, executor active threads close to max and rejected execution appears",
                        List.of("thread-pool-saturation"),
                        signal("prometheus", "metric", "executor_active_threads", "ANOMALY", "executor active threads near max", "executor_active_threads=max"),
                        signal("elk", "log", "RejectedExecutionException", "ANOMALY", "business executor rejected task", "RejectedExecutionException queue backlog")),
                evalCase("rag-thread-pool-tomcat", "Tomcat busy threads",
                        "tomcat busy threads remain high and request queue grows",
                        List.of("thread-pool-saturation"),
                        signal("prometheus", "metric", "tomcat_threads_busy", "ANOMALY", "Tomcat busy threads high", "tomcat_threads_busy close to max"),
                        signal("elk", "log", "queue_backlog", "ANOMALY", "request queue backlog", "thread pool queue backlog")),
                evalCase("rag-observability-gap", "Observability gap",
                        "Prometheus confirms 5xx, but ELK and SkyWalking return NO_ANOMALY for the same window",
                        List.of("observability-gap", "gateway-http-5xx"),
                        signal("prometheus", "metric", "http_5xx_rate_percent", "ANOMALY", "5xx rate increased", "http_5xx_rate_percent high"),
                        signal("elk", "log", "application_error", "NO_ANOMALY", "no application error logs", "NO_ANOMALY"),
                        signal("skywalking", "trace", "error_trace", "NO_ANOMALY", "no error traces", "NO_ANOMALY")),
                evalCase("rag-evidence-policy", "Evidence sufficiency policy",
                        "reviewer must distinguish symptom metrics from root-cause evidence and avoid inventing DB Redis JVM causes",
                        List.of("evidence-sufficiency-policy", "observability-gap"),
                        signal("prometheus", "metric", "http_5xx_rate_percent", "ANOMALY", "only symptom metric exists", "5xx symptom only"),
                        signal("elk", "log", "root_cause_log", "NO_ANOMALY", "queried but no root cause log", "NO_ANOMALY"))
                ,
                evalCase("rag-payment-callback-idempotency", "Payment callback idempotency",
                        "payment callback retry storm, duplicate notify and order status conflict after pay success",
                        List.of("payment-callback-idempotency"),
                        signal("elk", "log", "duplicate_payment_callback", "ANOMALY", "same paymentNo entered grantBenefit twice", "duplicate notify payment callback idempotency order status conflict"),
                        signal("skywalking", "trace", "payment_callback_trace", "ANOMALY", "callback retry executes delivery message twice", "payment callback retry duplicate side effect")),
                evalCase("rag-kubernetes-crashloop-oom", "Kubernetes CrashLoop OOMKilled",
                        "deployment unavailable because pod restarts repeatedly with CrashLoopBackOff and OOMKilled",
                        List.of("kubernetes-pod-crashloop"),
                        signal("prometheus", "metric", "kube_pod_container_status_restarts_total", "ANOMALY", "pod restart count increasing", "CrashLoopBackOff pod restart OOMKilled"),
                        signal("elk", "log", "container_exit", "ANOMALY", "container exit code 137", "OOMKilled exit code 137 liveness probe failed")),
                evalCase("rag-database-deadlock", "Database deadlock transaction",
                        "checkout update failed with Deadlock found and transaction rollback during inventory update",
                        List.of("database-deadlock-transaction"),
                        signal("elk", "log", "Deadlock found", "ANOMALY", "InnoDB deadlock found when trying to get lock", "Deadlock found transaction rollback"),
                        signal("skywalking", "trace", "db_update_span", "ANOMALY", "UPDATE inventory lock wait timeout", "Lock wait timeout exceeded gap lock")),
                evalCase("rag-cache-hotkey-breakdown", "Cache hot key breakdown",
                        "Redis timeout and DB QPS spike after hot key ttl expired, cache miss storm",
                        List.of("cache-avalanche-hotkey"),
                        signal("elk", "log", "RedisCommandTimeoutException", "ANOMALY", "Redis command timeout with hot key", "hot key cache breakdown RedisCommandTimeoutException"),
                        signal("prometheus", "metric", "cache_miss_rate", "ANOMALY", "cache miss rate and DB QPS increased", "cache avalanche ttl expired miss storm")),
                evalCase("rag-gray-release-risk", "Gray release risk approval",
                        "agent generated patch for order payment flow and needs canary rollback approval gate",
                        List.of("gray-release-risk"),
                        signal("context", "release", "patch_scope", "OBSERVED", "patch touches payment order status and transaction code", "gray release canary rollback approval gate"),
                        signal("prometheus", "metric", "post_release_5xx", "OBSERVED", "observe 5xx and p99 after canary", "release risk feature flag rollback"))
        );
    }

    private OpsRunbookRagEvalCase evalCase(String caseId,
                                           String caseName,
                                           String problem,
                                           List<String> expectedRunbookIds,
                                           EvidenceSignalEntity... signals) {
        return OpsRunbookRagEvalCase.builder()
                .caseId(caseId)
                .caseName(caseName)
                .serviceName("ops-demo-service")
                .problem(problem)
                .expectedRunbookIds(expectedRunbookIds)
                .evidenceSignals(List.of(signals))
                .build();
    }

    private EvidenceSignalEntity signal(String source,
                                        String evidenceType,
                                        String name,
                                        String status,
                                        String summary,
                                        String rawEvidence) {
        return EvidenceSignalEntity.builder()
                .signalId("rag-signal-" + UUID.randomUUID())
                .source(source)
                .evidenceType(evidenceType)
                .name(name)
                .status(status)
                .entity("ops-demo-service")
                .severity("P1")
                .timeWindow("last_15m")
                .summary(summary)
                .rawEvidence(rawEvidence)
                .build();
    }

    private String normalizeRunbookId(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("\\", "/")
                .replace(".md", "")
                .trim();
    }

    private String summarizeException(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000) + "...";
    }

}

