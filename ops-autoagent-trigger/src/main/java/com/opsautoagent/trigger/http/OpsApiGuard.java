package com.opsautoagent.trigger.http;

import com.opsautoagent.api.dto.OpsIncidentAnalyzeRequestDTO;
import com.opsautoagent.api.dto.OpsAlertWebhookRequestDTO;
import com.opsautoagent.domain.ops.adapter.repository.IOpsGovernanceRepository;
import com.opsautoagent.domain.ops.model.entity.OpsAuditLogEntity;
import com.opsautoagent.domain.ops.service.OpsSensitiveDataMasker;
import com.alibaba.fastjson.JSON;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class OpsApiGuard {

    private static final String TOKEN_HEADER = "X-Ops-Token";
    private static final String USER_HEADER = "X-Ops-User";

    @Value("${ops.security.enabled:false}")
    private boolean securityEnabled;

    @Value("${ops.security.api-token:}")
    private String apiToken;

    @Value("${ops.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${ops.rate-limit.max-requests:20}")
    private int maxRequests;

    @Value("${ops.rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Resource
    private IOpsGovernanceRepository governanceRepository;

    @Resource
    private OpsSensitiveDataMasker sensitiveDataMasker;

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public String checkAnalyzeRequest(OpsIncidentAnalyzeRequestDTO request, String sessionId) {
        HttpServletRequest servletRequest = currentRequest();
        String resource = request == null ? "ops-incident" : "ops-incident:" + request.getServiceName();

        String authMessage = checkToken(servletRequest);
        if (authMessage != null) {
            audit(servletRequest, sessionId, null, "ANALYZE_INCIDENT", resource, request, "DENY", authMessage);
            return authMessage;
        }

        String rateMessage = checkRateLimit(servletRequest, request);
        if (rateMessage != null) {
            audit(servletRequest, sessionId, null, "ANALYZE_INCIDENT", resource, request, "DENY", rateMessage);
            return rateMessage;
        }

        audit(servletRequest, sessionId, null, "ANALYZE_INCIDENT", resource, request, "ALLOW", "request accepted");
        return null;
    }

    public String checkRecordQuery(String diagnosisId, String sessionId) {
        HttpServletRequest servletRequest = currentRequest();
        String authMessage = checkToken(servletRequest);
        if (authMessage != null) {
            audit(servletRequest, sessionId, diagnosisId, "QUERY_DIAGNOSIS_RECORD", "ops-incident-record:" + diagnosisId,
                    diagnosisId, "DENY", authMessage);
            return authMessage;
        }
        audit(servletRequest, sessionId, diagnosisId, "QUERY_DIAGNOSIS_RECORD", "ops-incident-record:" + diagnosisId,
                diagnosisId, "ALLOW", "request accepted");
        return null;
    }

    public String checkAlertWebhookRequest(OpsAlertWebhookRequestDTO request, String sessionId) {
        HttpServletRequest servletRequest = currentRequest();
        String serviceName = extractServiceName(request);
        String resource = "ops-alert-webhook:" + serviceName;

        String authMessage = checkToken(servletRequest);
        if (authMessage != null) {
            audit(servletRequest, sessionId, null, "RECEIVE_ALERT_WEBHOOK", resource, request, "DENY", authMessage);
            return authMessage;
        }

        String rateMessage = checkRateLimit(servletRequest, serviceName);
        if (rateMessage != null) {
            audit(servletRequest, sessionId, null, "RECEIVE_ALERT_WEBHOOK", resource, request, "DENY", rateMessage);
            return rateMessage;
        }

        audit(servletRequest, sessionId, null, "RECEIVE_ALERT_WEBHOOK", resource, request, "ALLOW", "request accepted");
        return null;
    }

    private String checkToken(HttpServletRequest request) {
        if (!securityEnabled) {
            return null;
        }
        if (isBlank(apiToken)) {
            return "ops security is enabled, but ops.security.api-token is blank";
        }
        String requestToken = request == null ? null : request.getHeader(TOKEN_HEADER);
        if (!Objects.equals(apiToken, requestToken)) {
            return "invalid ops api token";
        }
        return null;
    }

    private String checkRateLimit(HttpServletRequest request, OpsIncidentAnalyzeRequestDTO body) {
        String serviceName = body == null || isBlank(body.getServiceName()) ? "unknown" : body.getServiceName();
        return checkRateLimit(request, serviceName);
    }

    private String checkRateLimit(HttpServletRequest request, String serviceName) {
        if (!rateLimitEnabled) {
            return null;
        }
        int safeMaxRequests = Math.max(1, maxRequests);
        long safeWindowMillis = Math.max(1, windowSeconds) * 1000L;
        String key = clientIp(request) + ":" + (isBlank(serviceName) ? "unknown" : serviceName);
        long now = System.currentTimeMillis();
        WindowCounter counter = counters.compute(key, (ignored, current) -> {
            if (current == null || now - current.windowStartMillis >= safeWindowMillis) {
                return new WindowCounter(now, new AtomicInteger(1));
            }
            current.count.incrementAndGet();
            return current;
        });
        if (counter.count.get() > safeMaxRequests) {
            return "rate limit exceeded, max " + safeMaxRequests + " requests per " + Math.max(1, windowSeconds) + " seconds";
        }
        return null;
    }

    private String extractServiceName(OpsAlertWebhookRequestDTO request) {
        if (request == null) {
            return "unknown";
        }
        if (request.getCommonLabels() != null) {
            String serviceName = firstNonBlank(
                    request.getCommonLabels().get("serviceName"),
                    request.getCommonLabels().get("service"),
                    request.getCommonLabels().get("application"),
                    request.getCommonLabels().get("app"),
                    request.getCommonLabels().get("job"));
            if (!isBlank(serviceName)) {
                return serviceName;
            }
        }
        if (request.getAlerts() != null && !request.getAlerts().isEmpty() && request.getAlerts().get(0) != null
                && request.getAlerts().get(0).getLabels() != null) {
            return firstNonBlank(
                    request.getAlerts().get(0).getLabels().get("serviceName"),
                    request.getAlerts().get(0).getLabels().get("service"),
                    request.getAlerts().get(0).getLabels().get("application"),
                    request.getAlerts().get(0).getLabels().get("app"),
                    request.getAlerts().get(0).getLabels().get("job"));
        }
        return "unknown";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void audit(HttpServletRequest request,
                       String sessionId,
                       String diagnosisId,
                       String action,
                       String resource,
                       Object requestBody,
                       String result,
                       String reason) {
        try {
            String requestJson = requestBody == null ? null : sensitiveDataMasker.mask(JSON.toJSONString(requestBody));
            governanceRepository.saveAuditLog(OpsAuditLogEntity.builder()
                    .auditId("audit-" + UUID.randomUUID())
                    .sessionId(sessionId)
                    .diagnosisId(diagnosisId)
                    .operatorId(operatorId(request))
                    .clientIp(clientIp(request))
                    .action(action)
                    .resource(resource)
                    .requestJson(abbreviate(requestJson, 6000))
                    .result(result)
                    .reason(abbreviate(sensitiveDataMasker.mask(reason), 1000))
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Save ops audit log failed. sessionId={}, action={}", sessionId, action, e);
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String operatorId(HttpServletRequest request) {
        if (request == null || isBlank(request.getHeader(USER_HEADER))) {
            return "anonymous";
        }
        return request.getHeader(USER_HEADER);
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (!isBlank(forwardedFor)) {
            int commaIndex = forwardedFor.indexOf(',');
            return commaIndex > 0 ? forwardedFor.substring(0, commaIndex).trim() : forwardedFor.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (!isBlank(realIp)) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private record WindowCounter(long windowStartMillis, AtomicInteger count) {
    }

}

