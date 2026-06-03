package com.opsautoagent.infrastructure.adapter.gateway.ops;

import com.opsautoagent.domain.ops.adapter.gateway.IOpsMetricGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsMcpToolGateway;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.MetricEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.OpsMcpToolResultEntity;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class PrometheusMetricGateway extends AbstractOpsHttpGateway implements IOpsMetricGateway {

    @Value("${ops.integrations.prometheus.base-url:}")
    private String baseUrl;

    @Value("${ops.integrations.prometheus.username:}")
    private String username;

    @Value("${ops.integrations.prometheus.password:}")
    private String password;

    @Autowired(required = false)
    private IOpsMcpToolGateway opsMcpToolGateway;

    @Value("${ops.integrations.mcp.prefer:true}")
    private boolean preferMcp;

    @Value("${ops.integrations.mcp.fallback-http:true}")
    private boolean fallbackHttp;

    @Value("${ops.integrations.mcp.grafana.mcp-id:5008}")
    private String grafanaMcpId;

    @Value("${ops.integrations.mcp.grafana.query-tool-name:query_prometheus}")
    private String grafanaQueryToolName;

    @Value("${ops.integrations.mcp.grafana.datasource-uid:}")
    private String grafanaDatasourceUid;

    @Override
    public MetricEvidenceEntity queryMetrics(IncidentCommandEntity command) {
        if (isBlank(baseUrl) && !canUseMcp()) {
            saveToolCallLog("prometheus", command.getSessionId(), command.getDiagnosisId(), "not-configured",
                    "base-url is blank", "metric query skipped", null, 0L, false,
                    "ops.integrations.prometheus.base-url is blank");
            return MetricEvidenceEntity.builder()
                    .source("prometheus")
                    .available(false)
                    .summary("Prometheus/Grafana base-url is not configured; live metric query is skipped.")
                    .observations(List.of(
                            "Configure ops.integrations.prometheus.base-url to query QPS, RT, error rate, JVM, CPU, thread, GC, and DB pool metrics.",
                            "The workflow will still use logs, traces, and user context for preliminary analysis."))
                    .rawData("")
                    .sourceMetadata(Map.of(
                            "sourceType", "PROMETHEUS",
                            "sourceMode", "UNCONFIGURED",
                            "baseUrl", value(baseUrl),
                            "mcpEnabled", canUseMcp(),
                            "fixtureFallback", false))
                    .build();
        }

        List<String> observations = new ArrayList<>();
        StringBuilder rawData = new StringBuilder();
        List<Map<String, String>> promQlQueries = new ArrayList<>();

        query(command, "traffic_qps", qps(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "http_5xx_qps", errorQps(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "http_5xx_rate_percent", errorRatePercent(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "http_avg_latency_seconds", avgLatency(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "http_p95_latency_seconds", latencyQuantile(command.getServiceName(), "0.95"), observations, rawData, promQlQueries);
        query(command, "http_p99_latency_seconds", latencyQuantile(command.getServiceName(), "0.99"), observations, rawData, promQlQueries);
        query(command, "process_cpu_usage", processCpu(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "system_cpu_usage", systemCpu(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "jvm_memory_used_bytes", jvmMemory(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "jvm_threads_live", jvmThreads(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "jvm_gc_pause_avg_seconds", gcPauseAvg(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "tomcat_threads_busy", tomcatThreadsBusy(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "executor_active_threads", executorActiveThreads(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "hikari_connections_active", hikariActive(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "hikari_connections_max", hikariMax(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "hikari_connections_usage_percent", hikariUsagePercent(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "hikari_connections_pending", hikariPending(command.getServiceName()), observations, rawData, promQlQueries);
        query(command, "hikari_connection_timeout_total", hikariTimeout(command.getServiceName()), observations, rawData, promQlQueries);

        return MetricEvidenceEntity.builder()
                .source("prometheus")
                .available(true)
                .summary("Collected Prometheus metrics and converted them into readable observations for traffic, 5xx, latency, CPU, JVM, thread, GC, Tomcat/executor, and Hikari pool dimensions.")
                .observations(observations)
                .rawData(abbreviate(rawData.toString(), 12000))
                .sourceMetadata(Map.of(
                        "sourceType", "PROMETHEUS",
                        "sourceMode", canUseMcp() ? "REAL_MCP_OR_HTTP" : "REAL_HTTP",
                        "baseUrl", value(baseUrl),
                        "mcpEnabled", canUseMcp(),
                        "timeWindow", command.getStartTime() + " ~ " + command.getEndTime(),
                        "queries", promQlQueries,
                        "fixtureFallback", false))
                .build();
    }

    private void query(IncidentCommandEntity command, String metricName, String promQl, List<String> observations,
                       StringBuilder rawData, List<Map<String, String>> promQlQueries) {
        promQlQueries.add(Map.of("metricName", metricName, "promQl", promQl));
        try {
            String response = queryByMcp(command, metricName, promQl);
            if (response == null) {
                String url = buildQueryRangeUrl(command, promQl);
                response = httpGet("prometheus", command.getSessionId(), command.getDiagnosisId(), url,
                        metricName + " PromQL: " + promQl, username, password);
            }
            MetricSnapshot snapshot = parseSnapshot(metricName, response);
            observations.add(snapshot.toObservation());
            rawData.append("\n").append(snapshot.toEvidenceLine());
        } catch (Exception e) {
            log.warn("Prometheus query failed. service={}, metric={}", command.getServiceName(), metricName, e);
            observations.add(metricName + " query failed: " + e.getMessage());
        }
    }

    private String queryByMcp(IncidentCommandEntity command, String metricName, String promQl) {
        if (!canUseMcp()) {
            return null;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", promQl);
        if (!isBlank(grafanaDatasourceUid)) {
            args.put("datasource", grafanaDatasourceUid);
        }
        args.put("start", command.getStartTime());
        args.put("end", command.getEndTime());

        OpsMcpToolResultEntity result = opsMcpToolGateway.callTool(command, grafanaMcpId, grafanaQueryToolName, args);
        if (result.isSuccess()) {
            return result.getContent();
        }
        if (!fallbackHttp || isBlank(baseUrl)) {
            throw new IllegalStateException("MCP Grafana query failed for " + metricName + ": " + result.getErrorMessage());
        }
        log.warn("MCP Grafana query failed, fallback to Prometheus HTTP. metric={}, error={}",
                metricName, result.getErrorMessage());
        return null;
    }

    private boolean canUseMcp() {
        return preferMcp && opsMcpToolGateway != null && !isBlank(grafanaMcpId);
    }

    private MetricSnapshot parseSnapshot(String metricName, String response) {
        try {
            JSONObject root = JSON.parseObject(response);
            JSONArray result = root.getJSONObject("data").getJSONArray("result");
            if (result == null || result.isEmpty()) {
                return MetricSnapshot.noData(metricName);
            }

            double latest = 0D;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            double sum = 0D;
            int count = 0;

            for (int i = 0; i < result.size(); i++) {
                JSONObject series = result.getJSONObject(i);
                JSONArray values = series.getJSONArray("values");
                if (values == null) {
                    continue;
                }
                for (int j = 0; j < values.size(); j++) {
                    JSONArray point = values.getJSONArray(j);
                    if (point == null || point.size() < 2) {
                        continue;
                    }
                    double value = parseDouble(point.getString(1));
                    if (Double.isNaN(value) || Double.isInfinite(value)) {
                        continue;
                    }
                    latest = value;
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                    sum += value;
                    count++;
                }
            }

            if (count == 0) {
                return MetricSnapshot.noData(metricName);
            }
            return new MetricSnapshot(metricName, latest, min, max, sum / count, count);
        } catch (Exception e) {
            return MetricSnapshot.unparsed(metricName, e.getMessage());
        }
    }

    private double parseDouble(String value) {
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return 0D;
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record MetricSnapshot(String metricName,
                                  double latest,
                                  double min,
                                  double max,
                                  double avg,
                                  int points,
                                  boolean noData,
                                  String parseError) {

        private MetricSnapshot(String metricName, double latest, double min, double max, double avg, int points) {
            this(metricName, latest, min, max, avg, points, false, null);
        }

        static MetricSnapshot noData(String metricName) {
            return new MetricSnapshot(metricName, 0D, 0D, 0D, 0D, 0, true, null);
        }

        static MetricSnapshot unparsed(String metricName, String parseError) {
            return new MetricSnapshot(metricName, 0D, 0D, 0D, 0D, 0, true, parseError);
        }

        String toObservation() {
            if (parseError != null) {
                return "UNKNOWN: " + metricName + " returned data but could not be parsed: " + parseError;
            }
            if (noData) {
                return "NO_DATA: " + metricName + " has no Prometheus series in this window.";
            }

            String level = anomalyLevel();
            return String.format(Locale.ROOT, "%s: %s latest=%s, max=%s, avg=%s, points=%d%s",
                    level, metricName, format(latest), format(max), format(avg), points, adviceSuffix(level));
        }

        String toEvidenceLine() {
            if (parseError != null) {
                return metricName + ": parse_error=" + parseError;
            }
            if (noData) {
                return metricName + ": no_data";
            }
            return String.format(Locale.ROOT, "%s: latest=%s, min=%s, max=%s, avg=%s, points=%d, level=%s",
                    metricName, format(latest), format(min), format(max), format(avg), points, anomalyLevel());
        }

        private String anomalyLevel() {
            return switch (metricName) {
                case "http_5xx_qps" -> max > 0D ? "ANOMALY: http_5xx_detected" : "OK";
                case "http_5xx_rate_percent" -> max >= 1D ? "ANOMALY: http_5xx_rate_high" : "OK";
                case "http_avg_latency_seconds" -> max >= 1D ? "ANOMALY: avg_latency_high" : "OK";
                case "http_p95_latency_seconds" -> max >= 1D ? "ANOMALY: p95_latency_high" : "OK";
                case "http_p99_latency_seconds" -> max >= 1D ? "ANOMALY: p99_latency_high" : "OK";
                case "process_cpu_usage", "system_cpu_usage" -> max >= 0.8D ? "ANOMALY: cpu_usage_high" : "OK";
                case "jvm_gc_pause_avg_seconds" -> max >= 0.2D ? "ANOMALY: gc_pause_high" : "OK";
                case "hikari_connections_usage_percent" -> max >= 80D ? "ANOMALY: hikari_pool_high_usage" : "OK";
                case "hikari_connections_pending" -> max > 0D ? "ANOMALY: hikari_pending_connections" : "OK";
                case "hikari_connection_timeout_total" -> max > 0D ? "ANOMALY: hikari_connection_timeout" : "OK";
                default -> "OK";
            };
        }

        private String adviceSuffix(String level) {
            if (!level.startsWith("ANOMALY")) {
                return "";
            }
            return switch (metricName) {
                case "http_5xx_qps", "http_5xx_rate_percent" -> ", evidence=接口 5xx 在窗口内出现";
                case "http_avg_latency_seconds", "http_p95_latency_seconds", "http_p99_latency_seconds" -> ", evidence=接口耗时在窗口内升高";
                case "hikari_connections_usage_percent", "hikari_connections_pending", "hikari_connection_timeout_total" -> ", evidence=数据库连接池出现压力信号";
                default -> ", evidence=资源指标出现异常";
            };
        }

        private String format(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return "0";
            }
            if (Math.abs(value) >= 1000D) {
                return String.format(Locale.ROOT, "%.0f", value);
            }
            if (Math.abs(value) >= 10D) {
                return String.format(Locale.ROOT, "%.2f", value);
            }
            return String.format(Locale.ROOT, "%.4f", value);
        }
    }

    private String qps(String serviceName) {
        return joinOr(
                "sum(rate(http_server_requests_seconds_count" + serviceLabel(serviceName, "application") + "[1m]))",
                "sum(rate(http_server_requests_seconds_count" + serviceLabel(serviceName, "job") + "[1m]))",
                "sum(rate(http_server_requests_seconds_count" + serviceLabel(serviceName, "service") + "[1m]))",
                "sum(rate(http_server_requests_seconds_count" + serviceLabel(serviceName, "") + "[1m]))");
    }

    private String errorQps(String serviceName) {
        return joinOr(
                "sum(rate(http_server_requests_seconds_count" + serviceLabel(serviceName, "application", "status=~\"5..\"") + "[1m]))",
                "sum(rate(http_server_requests_seconds_count" + serviceLabel(serviceName, "job", "status=~\"5..\"") + "[1m]))",
                "sum(rate(http_server_requests_seconds_count" + serviceLabel(serviceName, "service", "status=~\"5..\"") + "[1m]))",
                "sum(rate(http_server_requests_seconds_count" + selector("status=~\"5..\"") + "[1m]))");
    }

    private String errorRatePercent(String serviceName) {
        return "100 * (" + errorQps(serviceName) + ") / clamp_min((" + qps(serviceName) + "), 0.001)";
    }

    private String avgLatency(String serviceName) {
        String sum = joinOr(
                "sum(rate(http_server_requests_seconds_sum" + serviceLabel(serviceName, "application") + "[1m]))",
                "sum(rate(http_server_requests_seconds_sum" + serviceLabel(serviceName, "job") + "[1m]))",
                "sum(rate(http_server_requests_seconds_sum" + serviceLabel(serviceName, "service") + "[1m]))",
                "sum(rate(http_server_requests_seconds_sum" + serviceLabel(serviceName, "") + "[1m]))");
        return "(" + sum + ") / clamp_min((" + qps(serviceName) + "), 0.001)";
    }

    private String latencyQuantile(String serviceName, String quantile) {
        return joinOr(
                "histogram_quantile(" + quantile + ", sum(rate(http_server_requests_seconds_bucket" + serviceLabel(serviceName, "application") + "[1m])) by (le))",
                "histogram_quantile(" + quantile + ", sum(rate(http_server_requests_seconds_bucket" + serviceLabel(serviceName, "job") + "[1m])) by (le))",
                "histogram_quantile(" + quantile + ", sum(rate(http_server_requests_seconds_bucket" + serviceLabel(serviceName, "service") + "[1m])) by (le))",
                "histogram_quantile(" + quantile + ", sum(rate(http_server_requests_seconds_bucket" + serviceLabel(serviceName, "") + "[1m])) by (le))");
    }

    private String processCpu(String serviceName) {
        return metricByService("process_cpu_usage", serviceName);
    }

    private String systemCpu(String serviceName) {
        return metricByService("system_cpu_usage", serviceName);
    }

    private String jvmMemory(String serviceName) {
        return "sum(" + metricByService("jvm_memory_used_bytes", serviceName) + ")";
    }

    private String jvmThreads(String serviceName) {
        return metricByService("jvm_threads_live_threads", serviceName);
    }

    private String gcPauseAvg(String serviceName) {
        String sum = joinOr(
                "sum(rate(jvm_gc_pause_seconds_sum" + serviceLabel(serviceName, "application") + "[1m]))",
                "sum(rate(jvm_gc_pause_seconds_sum" + serviceLabel(serviceName, "job") + "[1m]))",
                "sum(rate(jvm_gc_pause_seconds_sum" + serviceLabel(serviceName, "service") + "[1m]))",
                "sum(rate(jvm_gc_pause_seconds_sum" + serviceLabel(serviceName, "") + "[1m]))");
        String count = joinOr(
                "sum(rate(jvm_gc_pause_seconds_count" + serviceLabel(serviceName, "application") + "[1m]))",
                "sum(rate(jvm_gc_pause_seconds_count" + serviceLabel(serviceName, "job") + "[1m]))",
                "sum(rate(jvm_gc_pause_seconds_count" + serviceLabel(serviceName, "service") + "[1m]))",
                "sum(rate(jvm_gc_pause_seconds_count" + serviceLabel(serviceName, "") + "[1m]))");
        return "(" + sum + ") / clamp_min((" + count + "), 0.001)";
    }

    private String tomcatThreadsBusy(String serviceName) {
        return metricByService("tomcat_threads_busy_threads", serviceName);
    }

    private String executorActiveThreads(String serviceName) {
        return joinOr(
                metricByService("executor_active_threads", serviceName),
                metricByService("executor_pool_active_threads", serviceName));
    }

    private String hikariActive(String serviceName) {
        return joinOr(
                metricByService("hikaricp_connections_active", serviceName),
                metricByService("hikari_connections_active", serviceName));
    }

    private String hikariMax(String serviceName) {
        return joinOr(
                metricByService("hikaricp_connections_max", serviceName),
                metricByService("hikari_connections_max", serviceName));
    }

    private String hikariUsagePercent(String serviceName) {
        return "100 * (" + hikariActive(serviceName) + ") / clamp_min((" + hikariMax(serviceName) + "), 1)";
    }

    private String hikariPending(String serviceName) {
        return joinOr(
                metricByService("hikaricp_connections_pending", serviceName),
                metricByService("hikari_connections_pending", serviceName));
    }

    private String hikariTimeout(String serviceName) {
        return joinOr(
                metricByService("hikaricp_connections_timeout_total", serviceName),
                metricByService("hikari_connections_timeout_total", serviceName));
    }

    private String metricByService(String metricName, String serviceName) {
        return joinOr(
                metricName + serviceLabel(serviceName, "application"),
                metricName + serviceLabel(serviceName, "job"),
                metricName + serviceLabel(serviceName, "service"),
                metricName + serviceLabel(serviceName, ""));
    }

    private String serviceLabel(String serviceName, String serviceLabelName) {
        if (isBlank(serviceLabelName)) {
            return "{}";
        }
        return "{" + serviceLabelName + "=\"" + escapeLabelValue(serviceName) + "\"}";
    }

    private String serviceLabel(String serviceName, String serviceLabelName, String extraLabel) {
        if (isBlank(serviceLabelName)) {
            return selector(extraLabel);
        }
        return "{" + serviceLabelName + "=\"" + escapeLabelValue(serviceName) + "\"," + extraLabel + "}";
    }

    private String selector(String labelExpression) {
        return "{" + labelExpression + "}";
    }

    private String joinOr(String... expressions) {
        return String.join(" or ", expressions);
    }

    private String escapeLabelValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String buildQueryRangeUrl(IncidentCommandEntity command, String promQl) {
        String encodedQuery = URLEncoder.encode(promQl, StandardCharsets.UTF_8);
        long start = parseEpochSecond(command.getStartTime());
        long end = parseEpochSecond(command.getEndTime());
        return trimEndSlash(baseUrl) + "/api/v1/query_range?query=" + encodedQuery
                + "&start=" + start
                + "&end=" + end
                + "&step=30";
    }

    private long parseEpochSecond(String value) {
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        );
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(value, formatter).atZone(ZoneId.systemDefault()).toEpochSecond();
            } catch (Exception ignored) {
                // try next formatter
            }
        }
        return System.currentTimeMillis() / 1000;
    }

}

