package com.opsautoagent.infrastructure.adapter.gateway.ops;

import com.opsautoagent.domain.ops.adapter.gateway.IOpsTraceGateway;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.TraceEvidenceEntity;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SkyWalkingTraceGateway extends AbstractOpsHttpGateway implements IOpsTraceGateway {

    @Value("${ops.integrations.skywalking.graphql-url:}")
    private String graphqlUrl;

    @Value("${ops.integrations.skywalking.username:}")
    private String username;

    @Value("${ops.integrations.skywalking.password:}")
    private String password;

    @Override
    public TraceEvidenceEntity queryTrace(IncidentCommandEntity command) {
        if (isBlank(graphqlUrl)) {
            saveToolCallLog("skywalking", command.getSessionId(), command.getDiagnosisId(), "not-configured",
                    "graphql-url is blank", "trace query skipped", null, 0L, false,
                    "ops.integrations.skywalking.graphql-url is blank");
            return TraceEvidenceEntity.builder()
                    .source("skywalking")
                    .available(false)
                    .summary("SkyWalking GraphQL url is not configured; live trace query is skipped.")
                    .spans(List.of(
                            "Configure ops.integrations.skywalking.graphql-url, for example http://127.0.0.1:12800/graphql.",
                            "SkyWalking will collect traceId details, service metrics, error trace samples, and slow trace samples."))
                    .rawData("")
                    .sourceMetadata(Map.of(
                            "sourceType", "SKYWALKING",
                            "sourceMode", "UNCONFIGURED",
                            "graphqlUrl", value(graphqlUrl),
                            "traceId", value(command.getTraceId()),
                            "endpoint", value(command.getProblem()),
                            "fixtureFallback", false))
                    .build();
        }

        List<String> spans = new ArrayList<>();
        StringBuilder rawData = new StringBuilder();

        if (isBlank(command.getTraceId())) {
            saveToolCallLog("skywalking", command.getSessionId(), command.getDiagnosisId(), graphqlUrl,
                    "traceId is blank", "traceId detail query skipped", null, 0L, false,
                    "traceId is blank");
            spans.add("traceId is not provided; precise single-trace span query is skipped.");
        } else {
            runQuery(command, "trace_detail", buildTraceDetailQuery(command.getTraceId()), spans, rawData);
        }

        if (isBlank(command.getServiceName())) {
            saveToolCallLog("skywalking", command.getSessionId(), command.getDiagnosisId(), graphqlUrl,
                    "serviceName is blank", "service queries skipped", null, 0L, false,
                    "serviceName is blank");
            spans.add("serviceName is not provided; service-specific queries (service metadata, metrics, error trace samples, slow trace samples) are skipped.");
        } else {
            String serviceId = resolveServiceId(command, spans, rawData);
            runQuery(command, "service_metrics", buildServiceMetricsQuery(command), spans, rawData);
            runQuery(command, "error_trace_samples", buildBasicTraceQuery(command, serviceId, "ERROR", "BY_START_TIME"), spans, rawData);
            runQuery(command, "slow_trace_samples", buildBasicTraceQuery(command, serviceId, "ALL", "BY_DURATION"), spans, rawData);
        }

        return TraceEvidenceEntity.builder()
                .source("skywalking")
                .available(rawData.length() > 0)
                .summary("Collected SkyWalking evidence for trace detail, service metrics, error traces, and slow traces in the incident window.")
                .spans(spans)
                .rawData(abbreviate(rawData.toString(), 12000))
                .sourceMetadata(Map.of(
                        "sourceType", "SKYWALKING",
                        "sourceMode", "REAL_HTTP",
                        "graphqlUrl", value(graphqlUrl),
                        "traceId", value(command.getTraceId()),
                        "serviceName", value(command.getServiceName()),
                        "endpoint", value(command.getProblem()),
                        "timeWindow", command.getStartTime() + " ~ " + command.getEndTime(),
                        "queriedSections", List.of("trace_detail", "service_metadata", "service_metrics", "error_trace_samples", "slow_trace_samples"),
                        "fixtureFallback", false))
                .build();
    }

    private void runQuery(IncidentCommandEntity command, String queryName, String query, List<String> spans, StringBuilder rawData) {
        try {
            String body = toGraphqlBody(query);
            String response = httpPostJson("skywalking", command.getSessionId(), command.getDiagnosisId(),
                    graphqlUrl, body, username, password);
            spans.add(queryName + " query succeeded.");
            rawData.append("\n\n### ").append(queryName).append("\n")
                    .append(response);
        } catch (Exception e) {
            log.warn("SkyWalking query failed. queryName={}, service={}, traceId={}",
                    queryName, command.getServiceName(), command.getTraceId(), e);
            spans.add(queryName + " query failed: " + e.getMessage());
        }
    }

    private String resolveServiceId(IncidentCommandEntity command, List<String> spans, StringBuilder rawData) {
        try {
            String query = buildSearchServiceQuery(command);
            String response = httpPostJson("skywalking", command.getSessionId(), command.getDiagnosisId(),
                    graphqlUrl, toGraphqlBody(query), username, password);
            rawData.append("\n\n### service_metadata\n")
                    .append(response);

            JSONObject root = JSON.parseObject(response);
            JSONArray services = root.getJSONObject("data") == null
                    ? null
                    : root.getJSONObject("data").getJSONArray("searchServices");
            if (services == null || services.isEmpty()) {
                spans.add("service_metadata query returned empty; fallback to serviceId=0.");
                return "0";
            }

            String targetServiceName = command.getServiceName();
            for (int i = 0; i < services.size(); i++) {
                JSONObject service = services.getJSONObject(i);
                String name = service.getString("name");
                if (targetServiceName != null && targetServiceName.equals(name)) {
                    String serviceId = service.getString("id");
                    spans.add("service_metadata query succeeded. serviceId=" + serviceId);
                    return serviceId == null ? "0" : serviceId;
                }
            }

            String fallbackServiceId = services.getJSONObject(0).getString("id");
            spans.add("service_metadata query succeeded with fuzzy match. serviceId=" + fallbackServiceId);
            return fallbackServiceId == null ? "0" : fallbackServiceId;
        } catch (Exception e) {
            log.warn("SkyWalking service metadata query failed. service={}", command.getServiceName(), e);
            spans.add("service_metadata query failed: " + e.getMessage() + "; fallback to serviceId=0.");
            return "0";
        }
    }

    private String buildSearchServiceQuery(IncidentCommandEntity command) {
        String serviceName = graphqlEscape(command.getServiceName());
        String start = skyWalkingTime(command.getStartTime());
        String end = skyWalkingTime(command.getEndTime());
        return """
                query searchService {
                  searchServices(duration: {
                    start: "%s",
                    end: "%s",
                    step: MINUTE
                  }, keyword: "%s") {
                    id
                    name
                  }
                }
                """.formatted(start, end, serviceName);
    }

    private String buildTraceDetailQuery(String traceId) {
        return """
                query queryTrace {
                  queryTrace(traceId: "%s") {
                    spans {
                      traceId
                      segmentId
                      spanId
                      parentSpanId
                      refs { traceId parentSegmentId parentSpanId type }
                      serviceCode
                      serviceInstanceName
                      endpointName
                      startTime
                      endTime
                      type
                      peer
                      isError
                      layer
                      component
                      tags { key value }
                      logs { time data { key value } }
                    }
                  }
                }
                """.formatted(graphqlEscape(traceId));
    }

    private String buildServiceMetricsQuery(IncidentCommandEntity command) {
        String serviceName = graphqlEscape(command.getServiceName());
        String start = skyWalkingTime(command.getStartTime());
        String end = skyWalkingTime(command.getEndTime());
        return """
                query serviceMetrics {
                  serviceCpm: readMetricsValues(condition: {
                    name: "service_cpm",
                    entity: {scope: Service, serviceName: "%s", normal: true}
                  }, duration: {start: "%s", end: "%s", step: MINUTE}) {
                    label
                    values { values { id value isEmptyValue } }
                  }
                  serviceRespTime: readMetricsValues(condition: {
                    name: "service_resp_time",
                    entity: {scope: Service, serviceName: "%s", normal: true}
                  }, duration: {start: "%s", end: "%s", step: MINUTE}) {
                    label
                    values { values { id value isEmptyValue } }
                  }
                  serviceSla: readMetricsValues(condition: {
                    name: "service_sla",
                    entity: {scope: Service, serviceName: "%s", normal: true}
                  }, duration: {start: "%s", end: "%s", step: MINUTE}) {
                    label
                    values { values { id value isEmptyValue } }
                  }
                  serviceApdex: readMetricsValues(condition: {
                    name: "service_apdex",
                    entity: {scope: Service, serviceName: "%s", normal: true}
                  }, duration: {start: "%s", end: "%s", step: MINUTE}) {
                    label
                    values { values { id value isEmptyValue } }
                  }
                }
                """.formatted(
                serviceName, start, end,
                serviceName, start, end,
                serviceName, start, end,
                serviceName, start, end);
    }

    private String buildBasicTraceQuery(IncidentCommandEntity command, String serviceId, String traceState, String queryOrder) {
        String start = skyWalkingTime(command.getStartTime());
        String end = skyWalkingTime(command.getEndTime());
        return """
                query basicTraces {
                  queryBasicTraces(condition: {
                    serviceId: "%s",
                    queryDuration: {
                      start: "%s",
                      end: "%s",
                      step: MINUTE
                    },
                    traceState: %s,
                    queryOrder: %s,
                    paging: {pageNum: 1, pageSize: 10}
                  }) {
                    traces {
                      segmentId
                      endpointNames
                      duration
                      start
                      isError
                      traceIds
                    }
                  }
                }
                """.formatted(graphqlEscape(serviceId), start, end, traceState, queryOrder);
    }

    private String toGraphqlBody(String query) {
        return """
                {
                  "query": "%s"
                }
                """.formatted(jsonEscape(query));
    }

    private String skyWalkingTime(String value) {
        List<DateTimeFormatter> inputFormatters = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        );
        for (DateTimeFormatter formatter : inputFormatters) {
            try {
                return LocalDateTime.parse(value, formatter).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"));
            } catch (Exception ignored) {
                // try next formatter
            }
        }
        return value == null ? "" : value;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String graphqlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

}
