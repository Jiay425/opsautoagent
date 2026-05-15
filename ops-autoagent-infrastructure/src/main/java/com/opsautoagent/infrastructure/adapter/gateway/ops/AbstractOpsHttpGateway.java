package com.opsautoagent.infrastructure.adapter.gateway.ops;

import com.opsautoagent.domain.ops.agent.governance.OpsToolProtocolResolver;
import com.opsautoagent.domain.ops.adapter.repository.IOpsGovernanceRepository;
import com.opsautoagent.domain.ops.model.entity.OpsToolCallLogEntity;
import com.opsautoagent.domain.ops.service.OpsSensitiveDataMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

@Slf4j
abstract class AbstractOpsHttpGateway {

    @Value("${ops.integrations.http.connect-timeout-seconds:5}")
    private long connectTimeoutSeconds;

    @Value("${ops.integrations.http.request-timeout-seconds:15}")
    private long requestTimeoutSeconds;

    @Resource
    private IOpsGovernanceRepository governanceRepository;

    @Resource
    private OpsSensitiveDataMasker sensitiveDataMasker;

    protected boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    protected String trimEndSlash(String value) {
        if (isBlank(value)) {
            return value;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    protected String httpGet(String toolName, String sessionId, String diagnosisId, String url, String requestSummary,
                             String username, String password) throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        Integer statusCode = null;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)))
                .GET();
        addBasicAuth(builder, username, password);
        try {
            HttpResponse<String> response = httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            statusCode = response.statusCode();
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + abbreviate(response.body(), 500));
            }
            saveToolCallLog(toolName, sessionId, diagnosisId, url, requestSummary, response.body(), statusCode,
                    System.currentTimeMillis() - start, true, null);
            return response.body();
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            saveToolCallLog(toolName, sessionId, diagnosisId, url, requestSummary, null, statusCode,
                    System.currentTimeMillis() - start, false, e.getMessage());
            throw e;
        }
    }

    protected String httpPostJson(String toolName, String sessionId, String diagnosisId, String url, String body,
                                  String username, String password) throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        Integer statusCode = null;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        addBasicAuth(builder, username, password);
        try {
            HttpResponse<String> response = httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            statusCode = response.statusCode();
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + abbreviate(response.body(), 500));
            }
            saveToolCallLog(toolName, sessionId, diagnosisId, url, body, response.body(), statusCode,
                    System.currentTimeMillis() - start, true, null);
            return response.body();
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            saveToolCallLog(toolName, sessionId, diagnosisId, url, body, null, statusCode,
                    System.currentTimeMillis() - start, false, e.getMessage());
            throw e;
        }
    }

    protected String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    protected String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    protected void saveToolCallLog(String toolName,
                                   String sessionId,
                                   String diagnosisId,
                                   String target,
                                   String requestSummary,
                                   String responseSummary,
                                   Integer statusCode,
                                   long costMillis,
                                   boolean success,
                                   String errorMessage) {
        try {
            governanceRepository.saveToolCallLog(OpsToolCallLogEntity.builder()
                    .callId("tool-" + UUID.randomUUID())
                    .sessionId(sessionId)
                    .diagnosisId(diagnosisId)
                    .toolName(toolName)
                    .logicalToolName(OpsToolProtocolResolver.logicalToolNameOf(toolName, target))
                    .protocol(OpsToolProtocolResolver.protocolOf(toolName, target))
                    .governanceDecision(success ? "SUCCESS" : "FAILED")
                    .target(abbreviate(mask(target), 200))
                    .requestSummary(abbreviate(mask(requestSummary), 6000))
                    .responseSummary(abbreviate(mask(responseSummary), 6000))
                    .statusCode(statusCode)
                    .costMillis(costMillis)
                    .success(Boolean.toString(success))
                    .errorMessage(abbreviate(mask(errorMessage), 2000))
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Save ops tool call log failed. toolName={}, sessionId={}", toolName, sessionId, e);
        }
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
                .build();
    }

    private String mask(String value) {
        return sensitiveDataMasker.mask(value);
    }

    private void addBasicAuth(HttpRequest.Builder builder, String username, String password) {
        if (isBlank(username) || isBlank(password)) {
            return;
        }
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        builder.header("Authorization", "Basic " + token);
    }

}

