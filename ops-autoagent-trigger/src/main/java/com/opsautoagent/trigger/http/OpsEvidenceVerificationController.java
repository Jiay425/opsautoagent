package com.opsautoagent.trigger.http;

import com.opsautoagent.api.dto.OpsIncidentAnalyzeRequestDTO;
import com.opsautoagent.api.response.Response;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsLogGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsMetricGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsRunbookGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsTraceGateway;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.LogEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.MetricEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.RootCauseCandidateEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import com.opsautoagent.domain.ops.model.entity.TraceEvidenceEntity;
import com.opsautoagent.types.enums.ResponseCode;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ops/verify")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class OpsEvidenceVerificationController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private IOpsMetricGateway metricGateway;

    @Resource
    private IOpsLogGateway logGateway;

    @Resource
    private IOpsTraceGateway traceGateway;

    @Resource
    private IOpsRunbookGateway runbookGateway;

    @RequestMapping(value = "full-chain", method = RequestMethod.POST)
    public Response<Map<String, Object>> verifyFullChain(@RequestBody(required = false) OpsIncidentAnalyzeRequestDTO request) {
        IncidentCommandEntity command = buildCommand(request);

        MetricEvidenceEntity metricEvidence = metricGateway.queryMetrics(command);
        LogEvidenceEntity logEvidence = logGateway.queryLogs(command);
        TraceEvidenceEntity traceEvidence = traceGateway.queryTrace(command);
        List<RunbookMatchEntity> runbookMatches = runbookGateway.search(command, List.of(
                RootCauseCandidateEntity.builder()
                        .category("database")
                        .cause("Database or connection pool failure caused request errors")
                        .reasoning("Verify PgVector can retrieve the database connection pool runbook.")
                        .confidence(1)
                        .build(),
                RootCauseCandidateEntity.builder()
                        .category("downstream")
                        .cause("Dubbo/RPC timeout")
                        .reasoning("Verify PgVector can retrieve downstream timeout runbooks.")
                        .confidence(1)
                        .build()), 4);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diagnosisId", command.getDiagnosisId());
        result.put("sessionId", command.getSessionId());
        result.put("serviceName", command.getServiceName());
        result.put("window", command.getStartTime() + " ~ " + command.getEndTime());
        result.put("prometheus", prometheusResult(metricEvidence));
        result.put("elk", elkResult(logEvidence));
        result.put("skywalking", skyWalkingResult(traceEvidence));
        result.put("pgvector", pgVectorResult(runbookMatches));
        result.put("overallReady", overallReady(result));

        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(result)
                .build();
    }

    private IncidentCommandEntity buildCommand(OpsIncidentAnalyzeRequestDTO request) {
        LocalDateTime now = LocalDateTime.now();
        String serviceName = request == null || isBlank(request.getServiceName())
                ? "ops-demo-service" : request.getServiceName();
        String startTime = request == null || isBlank(request.getStartTime())
                ? now.minusMinutes(15).format(FORMATTER) : request.getStartTime();
        String endTime = request == null || isBlank(request.getEndTime())
                ? now.plusMinutes(1).format(FORMATTER) : request.getEndTime();
        String problem = request == null || isBlank(request.getProblem())
                ? "Verify real Prometheus, ELK, SkyWalking, and PgVector reads for production incident diagnosis."
                : request.getProblem();
        return IncidentCommandEntity.builder()
                .diagnosisId("verify-" + UUID.randomUUID())
                .sessionId(UUID.randomUUID().toString())
                .serviceName(serviceName)
                .startTime(startTime)
                .endTime(endTime)
                .problem(problem)
                .traceId(request == null ? null : request.getTraceId())
                .build();
    }

    private Map<String, Object> prometheusResult(MetricEvidenceEntity evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceReachable", evidence != null && evidence.isAvailable());
        result.put("hasMetricSeries", evidence != null && evidence.getObservations() != null
                && evidence.getObservations().stream()
                .anyMatch(item -> item != null && (item.startsWith("OK:") || item.startsWith("ANOMALY:"))));
        result.put("hasAnomaly", evidence != null && evidence.getObservations() != null
                && evidence.getObservations().stream().anyMatch(item -> item != null && item.startsWith("ANOMALY:")));
        result.put("summary", evidence == null ? "" : evidence.getSummary());
        result.put("observations", evidence == null ? List.of() : evidence.getObservations());
        return result;
    }

    private Map<String, Object> elkResult(LogEvidenceEntity evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceReachable", evidence != null && evidence.isAvailable());
        result.put("hasIncidentSamples", evidence != null && evidence.getErrorSamples() != null
                && evidence.getErrorSamples().stream().anyMatch(item -> item != null && !item.toLowerCase(Locale.ROOT).contains("zero matching")));
        result.put("summary", evidence == null ? "" : evidence.getSummary());
        result.put("samples", evidence == null ? List.of() : evidence.getErrorSamples());
        return result;
    }

    private Map<String, Object> skyWalkingResult(TraceEvidenceEntity evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceReachable", evidence != null && evidence.isAvailable());
        result.put("hasTraceData", evidence != null && containsAny(evidence.getRawData(), "queryBasicTraces", "queryTrace", "traces", "spans"));
        result.put("hasErrorOrSlowTraceSignal", evidence != null && containsAny(evidence.getRawData(), "\"isError\":true", "\"isError\": true", "duration"));
        result.put("summary", evidence == null ? "" : evidence.getSummary());
        result.put("spans", evidence == null ? List.of() : evidence.getSpans());
        return result;
    }

    private Map<String, Object> pgVectorResult(List<RunbookMatchEntity> matches) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean realPgVectorMatch = matches != null && matches.stream()
                .anyMatch(match -> match.getPath() != null && match.getPath().startsWith("pgvector:"));
        result.put("sourceReachable", realPgVectorMatch);
        result.put("hasRunbookMatches", matches != null && !matches.isEmpty());
        result.put("matches", matches == null ? List.of() : matches);
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean overallReady(Map<String, Object> result) {
        return List.of("prometheus", "elk", "skywalking", "pgvector").stream()
                .map(key -> (Map<String, Object>) result.get(key))
                .allMatch(item -> Boolean.TRUE.equals(item.get("sourceReachable")));
    }

    private boolean containsAny(String value, String... keywords) {
        if (value == null || keywords == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

