package com.opsautoagent.infrastructure.adapter.gateway.ops;

import com.opsautoagent.domain.ops.adapter.gateway.IOpsLogGateway;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsMcpToolGateway;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.LogEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.OpsMcpToolResultEntity;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ElkLogGateway extends AbstractOpsHttpGateway implements IOpsLogGateway {

    @Value("${ops.integrations.elk.base-url:}")
    private String baseUrl;

    @Value("${ops.integrations.elk.index-pattern:logs-*}")
    private String indexPattern;

    @Value("${ops.integrations.elk.username:}")
    private String username;

    @Value("${ops.integrations.elk.password:}")
    private String password;

    @Autowired(required = false)
    private IOpsMcpToolGateway opsMcpToolGateway;

    @Value("${ops.integrations.mcp.prefer:true}")
    private boolean preferMcp;

    @Value("${ops.integrations.mcp.fallback-http:true}")
    private boolean fallbackHttp;

    @Value("${ops.integrations.mcp.elasticsearch.mcp-id:5007}")
    private String elasticsearchMcpId;

    @Value("${ops.integrations.mcp.elasticsearch.search-tool-name:search}")
    private String elasticsearchSearchToolName;

    @Override
    public LogEvidenceEntity queryLogs(IncidentCommandEntity command) {
        if (isBlank(baseUrl) && !canUseMcp()) {
            saveToolCallLog("elk", command.getSessionId(), command.getDiagnosisId(), "not-configured",
                    "base-url is blank", "log query skipped", null, 0L, false,
                    "ops.integrations.elk.base-url is blank");
            return LogEvidenceEntity.builder()
                    .source("elk")
                    .available(false)
                    .summary("ELK/Elasticsearch base-url is not configured; live log query is skipped.")
                    .errorSamples(List.of(
                            "Configure ops.integrations.elk.base-url and ops.integrations.elk.index-pattern.",
                            "Log search will use serviceName, incident window, ERROR/Exception, and problem keywords."))
                    .rawData("")
                    .build();
        }

        try {
            String body = buildSearchBody(command);
            String response = queryByMcp(command, body);
            if (response == null) {
                String url = trimEndSlash(baseUrl) + "/" + indexPattern + "/_search";
                response = httpPostJson("elk", command.getSessionId(), command.getDiagnosisId(), url, body, username, password);
            }
            List<String> samples = extractSamples(response);
            long totalHits = extractTotalHits(response);
            return LogEvidenceEntity.builder()
                    .source("elk")
                    .available(true)
                    .summary(totalHits > 0
                            ? "Collected " + totalHits + " ERROR/Exception log samples from real Elasticsearch for the incident window."
                            : "Elasticsearch query succeeded, but no matching ERROR/Exception log was found in the incident window.")
                    .errorSamples(samples.isEmpty()
                            ? List.of("ELK real query succeeded but returned zero matching incident log samples.")
                            : samples)
                    .rawData(abbreviate(response, 6000))
                    .build();
        } catch (Exception e) {
            log.warn("ELK log query failed. service={}", command.getServiceName(), e);
            return LogEvidenceEntity.builder()
                    .source("elk")
                    .available(false)
                    .summary("ELK query failed: " + e.getMessage())
                    .errorSamples(List.of("Check Elasticsearch base-url, index pattern, auth, and @timestamp field."))
                    .rawData("")
                    .build();
        }
    }

    private String buildSearchBody(IncidentCommandEntity command) {
        String serviceName = jsonEscape(command.getServiceName());
        String problem = jsonEscape(command.getProblem());
        String startTime = jsonEscape(command.getStartTime());
        String endTime = jsonEscape(command.getEndTime());

        return """
                {
                  "size": 10,
                  "sort": [{"@timestamp": {"order": "desc"}}],
                  "_source": ["@timestamp", "serviceName", "application", "traceId", "level", "message", "exception", "stack_trace"],
                  "query": {
                    "bool": {
                      "filter": [
                        {"range": {"@timestamp": {"gte": "%s", "lte": "%s", "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd HH:mm||strict_date_optional_time||epoch_millis"}}}
                      ],
                      "must": [
                        {
                          "bool": {
                            "should": [
                              {"match_phrase": {"serviceName": "%s"}},
                              {"match_phrase": {"application": "%s"}},
                              {"match_phrase": {"app": "%s"}}
                            ],
                            "minimum_should_match": 1
                          }
                        },
                        {
                          "bool": {
                            "should": [
                              {"match_phrase": {"level": "ERROR"}},
                              {"match_phrase": {"message": "Exception"}},
                              {"match_phrase": {"message": "error"}},
                              {"match_phrase": {"message": "%s"}}
                            ],
                            "minimum_should_match": 1
                          }
                        }
                      ]
                    }
                  }
                }
                """.formatted(startTime, endTime, serviceName, serviceName, serviceName, problem);
    }

    private String queryByMcp(IncidentCommandEntity command, String body) {
        if (!canUseMcp()) {
            return null;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("index", indexPattern);
        args.put("query", JSON.parseObject(body));
        args.put("body", JSON.parseObject(body));

        OpsMcpToolResultEntity result = opsMcpToolGateway.callTool(command, elasticsearchMcpId, elasticsearchSearchToolName, args);
        if (result.isSuccess()) {
            return result.getContent();
        }
        if (!fallbackHttp || isBlank(baseUrl)) {
            throw new IllegalStateException("MCP Elasticsearch query failed: " + result.getErrorMessage());
        }
        log.warn("MCP Elasticsearch query failed, fallback to Elasticsearch HTTP. error={}", result.getErrorMessage());
        return null;
    }

    private boolean canUseMcp() {
        return preferMcp && opsMcpToolGateway != null && !isBlank(elasticsearchMcpId);
    }

    private List<String> extractSamples(String response) {
        List<String> samples = new ArrayList<>();
        try {
            JSONArray hits = JSON.parseObject(response)
                    .getJSONObject("hits")
                    .getJSONArray("hits");
            if (hits == null) {
                return samples;
            }
            for (int i = 0; i < hits.size(); i++) {
                JSONObject source = hits.getJSONObject(i).getJSONObject("_source");
                if (source == null) {
                    continue;
                }
                String timestamp = source.getString("@timestamp");
                String level = firstNonBlank(source.getString("level"), source.getString("log_level"));
                String traceId = firstNonBlank(source.getString("traceId"), source.getString("trace_id"));
                String message = firstNonBlank(source.getString("message"), source.getString("exception"), source.getString("stack_trace"));
                samples.add(abbreviate(String.format("[%s] [%s] traceId=%s %s",
                        value(timestamp), value(level), value(traceId), value(message)), 800));
            }
        } catch (Exception e) {
            samples.add("ELK response parsed failed: " + e.getMessage());
        }
        return samples;
    }

    private long extractTotalHits(String response) {
        try {
            JSONObject total = JSON.parseObject(response)
                    .getJSONObject("hits")
                    .getJSONObject("total");
            return total == null ? 0L : total.getLongValue("value");
        } catch (Exception e) {
            return 0L;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

}

