package com.opsautoagent.trigger.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.Resource;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/ops/mock")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class OpsMockFaultController {

    @Resource(name = "mysqlDataSource")
    private DataSource mysqlDataSource;

    @Autowired(required = false)
    @Qualifier("pgVectorDataSource")
    private DataSource pgVectorDataSource;

    @Value("${ops.integrations.prometheus.base-url:}")
    private String prometheusBaseUrl;

    @Value("${ops.integrations.elk.base-url:}")
    private String elkBaseUrl;

    @Value("${ops.integrations.skywalking.graphql-url:}")
    private String skyWalkingGraphqlUrl;

    @RequestMapping(value = "order/create", method = RequestMethod.GET)
    public ResponseEntity<Map<String, Object>> createOrder(@RequestParam(value = "mode", defaultValue = "normal") String mode,
                                                           @RequestParam(value = "sleepMillis", defaultValue = "1200") long sleepMillis,
                                                           @RequestParam(value = "holdSeconds", defaultValue = "8") int holdSeconds) {
        long start = System.currentTimeMillis();
        String normalizedMode = mode == null ? "normal" : mode.trim().toLowerCase();

        try {
            switch (normalizedMode) {
                case "error" -> throw new IllegalStateException("Mock order create failed: database connection timeout");
                case "slow" -> sleep(limit(sleepMillis, 100, 10000));
                case "db" -> holdMysqlConnection(limit(holdSeconds, 1, 30));
                case "normal" -> {
                    // no-op
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported mock mode. Use normal, error, slow, or db.");
            }
            return ResponseEntity.ok(payload(normalizedMode, start, "OK"));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if ("error".equals(normalizedMode)) {
                log.warn("Mock fault endpoint generated expected 500 signal. serviceName=ops-demo-service, mode={}, uri=/api/v1/ops/mock/order/create, message={}",
                        normalizedMode, e.getMessage());
            } else {
                log.error("Mock fault endpoint failed. serviceName=ops-demo-service, mode={}, uri=/api/v1/ops/mock/order/create", normalizedMode, e);
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    @RequestMapping(value = "health", method = RequestMethod.GET)
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(payload("health", System.currentTimeMillis(), "OK"));
    }

    @RequestMapping(value = "environment", method = RequestMethod.GET)
    public ResponseEntity<Map<String, Object>> environment() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("time", LocalDateTime.now().toString());
        result.put("app", "UP");
        result.put("mysql", checkMysql());
        result.put("pgvector", checkPgVector());
        result.put("prometheus", checkPrometheus());
        result.put("elk", checkElk());
        result.put("skywalking", checkSkyWalking());
        return ResponseEntity.ok(result);
    }

    private void holdMysqlConnection(int seconds) throws Exception {
        try (Connection connection = mysqlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT SLEEP(?)")) {
            statement.setInt(1, seconds);
            statement.execute();
        }
    }

    private Map<String, Object> checkMysql() {
        Map<String, Object> mysql = new LinkedHashMap<>();
        try (Connection connection = mysqlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet resultSet = statement.executeQuery()) {
            mysql.put("status", resultSet.next() ? "UP" : "UNKNOWN");
        } catch (Exception e) {
            mysql.put("status", "DOWN");
            mysql.put("error", e.getMessage());
        }
        return mysql;
    }

    private Map<String, Object> checkPrometheus() {
        Map<String, Object> prometheus = new LinkedHashMap<>();
        if (prometheusBaseUrl == null || prometheusBaseUrl.trim().isEmpty()) {
            prometheus.put("status", "NOT_CONFIGURED");
            return prometheus;
        }
        try {
            String baseUrl = prometheusBaseUrl.endsWith("/")
                    ? prometheusBaseUrl.substring(0, prometheusBaseUrl.length() - 1)
                    : prometheusBaseUrl;
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest healthRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/-/healthy"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> healthResponse = client.send(healthRequest, HttpResponse.BodyHandlers.ofString());
            prometheus.put("status", healthResponse.statusCode() == 200 ? "UP" : "DOWN");
            prometheus.put("healthCode", healthResponse.statusCode());

            HttpRequest targetRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/targets?state=active"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> targetResponse = client.send(targetRequest, HttpResponse.BodyHandlers.ofString());
            prometheus.put("targetCode", targetResponse.statusCode());
            prometheus.put("appTargetVisible", targetResponse.body() != null
                    && targetResponse.body().contains("ops-demo-service")
                    && targetResponse.body().contains("\"health\":\"up\""));
        } catch (Exception e) {
            prometheus.put("status", "DOWN");
            prometheus.put("error", e.getMessage());
        }
        return prometheus;
    }

    private Map<String, Object> checkPgVector() {
        Map<String, Object> pgvector = new LinkedHashMap<>();
        if (pgVectorDataSource == null) {
            pgvector.put("status", "NOT_CONFIGURED");
            return pgvector;
        }
        try (Connection connection = pgVectorDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(1) FROM vector_store_openai");
             ResultSet resultSet = statement.executeQuery()) {
            pgvector.put("status", resultSet.next() ? "UP" : "UNKNOWN");
            pgvector.put("runbookVectorRows", resultSet.getInt(1));
        } catch (Exception e) {
            pgvector.put("status", "DOWN");
            pgvector.put("error", e.getMessage());
        }
        return pgvector;
    }

    private Map<String, Object> checkElk() {
        Map<String, Object> elk = new LinkedHashMap<>();
        if (elkBaseUrl == null || elkBaseUrl.trim().isEmpty()) {
            elk.put("status", "NOT_CONFIGURED");
            return elk;
        }
        try {
            String baseUrl = trimEndSlash(elkBaseUrl);
            HttpResponse<String> response = httpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/_cluster/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            elk.put("status", response.statusCode() < 300 ? "UP" : "DOWN");
            elk.put("healthCode", response.statusCode());
        } catch (Exception e) {
            elk.put("status", "DOWN");
            elk.put("error", e.getMessage());
        }
        return elk;
    }

    private Map<String, Object> checkSkyWalking() {
        Map<String, Object> skywalking = new LinkedHashMap<>();
        if (skyWalkingGraphqlUrl == null || skyWalkingGraphqlUrl.trim().isEmpty()) {
            skywalking.put("status", "NOT_CONFIGURED");
            return skywalking;
        }
        try {
            HttpResponse<String> response = httpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create(skyWalkingGraphqlUrl))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"query { version }\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            skywalking.put("status", response.statusCode() < 500 ? "UP" : "DOWN");
            skywalking.put("graphqlCode", response.statusCode());
        } catch (Exception e) {
            skywalking.put("status", "DOWN");
            skywalking.put("error", e.getMessage());
        }
        return skywalking;
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    private String trimEndSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private int limit(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private long limit(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private Map<String, Object> payload(String mode, long start, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("mode", mode);
        payload.put("costMillis", System.currentTimeMillis() - start);
        payload.put("time", LocalDateTime.now().toString());
        return payload;
    }

}

