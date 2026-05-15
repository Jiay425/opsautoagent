package com.opsautoagent.trigger.job;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "ops.demo.auto-seed", name = "enabled", havingValue = "true")
public class OpsDemoDataAutoSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${server.port:8099}")
    private int serverPort;

    @Value("${ops.demo.auto-seed.app-base-url:}")
    private String appBaseUrl;

    @Value("${ops.integrations.elk.base-url:}")
    private String elasticsearchUrl;

    @Value("${ops.demo.auto-seed.elasticsearch-index:ops-demo-service-log-auto}")
    private String elasticsearchIndex;

    @Value("${ops.demo.auto-seed.service-name:ops-demo-service}")
    private String serviceName;

    @Value("${ops.demo.auto-seed.start-delay-seconds:3}")
    private int startDelaySeconds;

    @Value("${ops.demo.auto-seed.error-count:12}")
    private int errorCount;

    @Value("${ops.demo.auto-seed.slow-count:6}")
    private int slowCount;

    @Value("${ops.demo.auto-seed.db-count:3}")
    private int dbCount;

    @Value("${ops.demo.auto-seed.trace-id:}")
    private String configuredTraceId;

    @Value("${ops.demo.auto-seed.elasticsearch-max-attempts:12}")
    private int elasticsearchMaxAttempts;

    @Value("${ops.demo.auto-seed.elasticsearch-retry-interval-seconds:5}")
    private int elasticsearchRetryIntervalSeconds;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        CompletableFuture.runAsync(this::seedQuietly);
    }

    private void seedQuietly() {
        try {
            sleep(Duration.ofSeconds(Math.max(0, startDelaySeconds)));
            String traceId = isBlank(configuredTraceId) ? "trace-ops-auto-" + UUID.randomUUID() : configuredTraceId;
            String timestamp = LocalDateTime.now().format(FORMATTER);

            boolean elasticsearchSeeded = seedElasticsearchEvidenceWithRetry(traceId, timestamp);
            generateFaultTraffic();

            if (elasticsearchSeeded) {
                log.info("Ops demo data auto seed completed. serviceName={}, traceId={}, elasticsearchIndex={}, elasticsearchSeeded=true",
                        serviceName, traceId, elasticsearchIndex);
            } else {
                log.warn("Ops demo data auto seed completed with Elasticsearch unavailable. serviceName={}, traceId={}, elasticsearchIndex={}, elasticsearchSeeded=false",
                        serviceName, traceId, elasticsearchIndex);
            }
        } catch (Exception e) {
            log.warn("Ops demo data auto seed failed. It will not block application startup.", e);
        }
    }

    private boolean seedElasticsearchEvidenceWithRetry(String traceId, String timestamp) {
        int maxAttempts = Math.max(1, elasticsearchMaxAttempts);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (seedElasticsearchEvidence(traceId, timestamp)) {
                return true;
            }
            if (attempt < maxAttempts) {
                log.warn("Ops demo Elasticsearch seed retry scheduled. attempt={}/{}, retryAfterSeconds={}",
                        attempt, maxAttempts, elasticsearchRetryIntervalSeconds);
                sleep(Duration.ofSeconds(Math.max(1, elasticsearchRetryIntervalSeconds)));
            }
        }
        return false;
    }

    private boolean seedElasticsearchEvidence(String traceId, String timestamp) {
        if (isBlank(elasticsearchUrl)) {
            log.warn("Ops demo Elasticsearch seed skipped, ops.integrations.elk.base-url is blank.");
            return false;
        }
        if (!ensureElasticsearchIndex()) {
            return false;
        }

        Map<String, Object> connectionTimeout = baseLog(traceId, timestamp);
        connectionTimeout.put("message", "SQLTimeoutException: HikariPool-1 - Connection is not available, request timed out after 30000ms");
        connectionTimeout.put("exception", "java.sql.SQLTimeoutException");
        connectionTimeout.put("stack_trace", "java.sql.SQLTimeoutException: Connection is not available\n at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:200)");

        Map<String, Object> orderError = baseLog(traceId, timestamp);
        orderError.put("message", "Mock order create failed: database connection timeout");
        orderError.put("exception", "java.lang.IllegalStateException");
        orderError.put("stack_trace", "java.lang.IllegalStateException: database connection timeout\n at com.opsautoagent.trigger.http.OpsMockFaultController.createOrder");

        boolean firstSaved = postJson(trimEndSlash(elasticsearchUrl) + "/" + elasticsearchIndex + "/_doc", JSON.toJSONString(connectionTimeout));
        boolean secondSaved = postJson(trimEndSlash(elasticsearchUrl) + "/" + elasticsearchIndex + "/_doc", JSON.toJSONString(orderError));
        boolean refreshed = post(trimEndSlash(elasticsearchUrl) + "/" + elasticsearchIndex + "/_refresh");
        return firstSaved && secondSaved && refreshed;
    }

    private boolean ensureElasticsearchIndex() {
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "@timestamp": {"type": "date", "format": "yyyy-MM-dd HH:mm:ss||strict_date_optional_time||epoch_millis"},
                      "serviceName": {"type": "keyword"},
                      "application": {"type": "keyword"},
                      "traceId": {"type": "keyword"},
                      "level": {"type": "keyword"},
                      "exception": {"type": "keyword"},
                      "message": {"type": "text"},
                      "stack_trace": {"type": "text"}
                    }
                  }
                }
                """;
        return putJson(trimEndSlash(elasticsearchUrl) + "/" + elasticsearchIndex, mapping);
    }

    private Map<String, Object> baseLog(String traceId, String timestamp) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("@timestamp", timestamp);
        doc.put("serviceName", serviceName);
        doc.put("application", serviceName);
        doc.put("traceId", traceId);
        doc.put("level", "ERROR");
        return doc;
    }

    private void generateFaultTraffic() {
        String baseUrl = resolveAppBaseUrl();
        for (int i = 0; i < errorCount; i++) {
            getIgnoreError(baseUrl + "/api/v1/ops/mock/order/create?mode=error");
        }
        for (int i = 0; i < slowCount; i++) {
            getIgnoreError(baseUrl + "/api/v1/ops/mock/order/create?mode=slow&sleepMillis=1600");
        }
        for (int i = 0; i < dbCount; i++) {
            getIgnoreError(baseUrl + "/api/v1/ops/mock/order/create?mode=db&holdSeconds=3");
        }
    }

    private boolean postJson(String url, String body) {
        try {
            HttpResponse<String> response = httpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Ops demo seed POST failed. url={}, status={}, body={}", url, response.statusCode(), response.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Ops demo seed POST failed. url={}", url, e);
            return false;
        }
    }

    private boolean putJson(String url, String body) {
        try {
            HttpResponse<String> response = httpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return true;
            }
            if (response.statusCode() == 400 && response.body() != null && response.body().contains("resource_already_exists_exception")) {
                return true;
            }
            log.warn("Ops demo seed PUT failed. url={}, status={}, body={}", url, response.statusCode(), response.body());
            return false;
        } catch (Exception e) {
            log.warn("Ops demo seed PUT failed. url={}", url, e);
            return false;
        }
    }

    private boolean post(String url) {
        try {
            HttpResponse<String> response = httpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Ops demo seed POST failed. url={}, status={}, body={}", url, response.statusCode(), response.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Ops demo seed POST failed. url={}", url, e);
            return false;
        }
    }

    private void getIgnoreError(String url) {
        try {
            httpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
            // Error-mode traffic intentionally returns HTTP 500.
        }
    }

    private String resolveAppBaseUrl() {
        if (!isBlank(appBaseUrl)) {
            return trimEndSlash(appBaseUrl);
        }
        return "http://127.0.0.1:" + serverPort;
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String trimEndSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

