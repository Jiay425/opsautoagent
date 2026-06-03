package com.opsautoagent.infrastructure.adapter.gateway.ops;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RunbookCrossEncoderReranker {

    @Value("${ops.runbook.rerank.enabled:false}")
    private boolean enabled;

    @Value("${ops.runbook.rerank.endpoint:}")
    private String endpoint;

    @Value("${ops.runbook.rerank.api-key:}")
    private String apiKey;

    @Value("${ops.runbook.rerank.model:bge-reranker-large}")
    private String model;

    @Value("${ops.runbook.rerank.candidate-top-n:30}")
    private int candidateTopN;

    @Value("${ops.runbook.rerank.timeout-ms:10000}")
    private long timeoutMs;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public List<RunbookMatchEntity> rerank(String query, List<RunbookMatchEntity> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (!enabled || endpoint == null || endpoint.isBlank()) {
            return assignRanks(candidates.stream().limit(Math.max(1, topK)).toList(),
                    "crossEncoder=disabled");
        }
        List<RunbookMatchEntity> safeCandidates = candidates.stream()
                .limit(Math.max(Math.max(1, topK), candidateTopN))
                .toList();
        try {
            Map<Integer, Integer> scores = callCrossEncoder(query, safeCandidates);
            if (scores.isEmpty()) {
                return assignRanks(safeCandidates.stream().limit(Math.max(1, topK)).toList(),
                        "crossEncoder=no_score");
            }
            List<RunbookMatchEntity> reranked = new ArrayList<>(safeCandidates);
            reranked.sort(Comparator.comparing(
                    match -> scores.getOrDefault(safeCandidates.indexOf(match), 0),
                    Comparator.reverseOrder()));
            for (int i = 0; i < reranked.size(); i++) {
                RunbookMatchEntity match = reranked.get(i);
                int score = scores.getOrDefault(safeCandidates.indexOf(match), 0);
                match.setCrossEncoderScore(score);
                match.setScore(Math.max(match.getScore() == null ? 0 : match.getScore(), score));
                match.setRankExplanation(append(match.getRankExplanation(),
                        "crossEncoderScore=" + score + ", model=" + model));
            }
            return assignRanks(reranked.stream().limit(Math.max(1, topK)).toList(), "crossEncoder=applied");
        } catch (Exception e) {
            log.warn("Runbook cross-encoder rerank failed, fallback to RRF order. endpoint={}", endpoint, e);
            return assignRanks(safeCandidates.stream().limit(Math.max(1, topK)).toList(),
                    "crossEncoder=failed:" + e.getMessage());
        }
    }

    private Map<Integer, Integer> callCrossEncoder(String query, List<RunbookMatchEntity> candidates) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", query == null ? "" : query);
        body.put("top_n", candidates.size());
        body.put("documents", candidates.stream()
                .map(match -> match.getContent() == null ? "" : match.getContent())
                .toList());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("rerank status=" + response.statusCode() + ", body=" + abbreviate(response.body(), 500));
        }
        return parseScores(response.body());
    }

    private Map<Integer, Integer> parseScores(String body) {
        Map<Integer, Integer> scores = new LinkedHashMap<>();
        JSONObject root = JSON.parseObject(body);
        JSONArray results = firstArray(root, "results", "data", "rerank_results");
        if (results == null) {
            return scores;
        }
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            int index = item.containsKey("index") ? item.getIntValue("index")
                    : item.containsKey("document_index") ? item.getIntValue("document_index") : i;
            double raw = firstDouble(item, "relevance_score", "score", "similarity");
            scores.put(index, Math.max(0, Math.min(100, (int) Math.round(raw <= 1D ? raw * 100D : raw))));
        }
        return scores;
    }

    private JSONArray firstArray(JSONObject root, String... keys) {
        for (String key : keys) {
            Object value = root.get(key);
            if (value instanceof JSONArray array) {
                return array;
            }
        }
        return null;
    }

    private double firstDouble(JSONObject item, String... keys) {
        for (String key : keys) {
            if (item.containsKey(key)) {
                return item.getDoubleValue(key);
            }
        }
        return 0D;
    }

    private List<RunbookMatchEntity> assignRanks(List<RunbookMatchEntity> matches, String reason) {
        for (int i = 0; i < matches.size(); i++) {
            RunbookMatchEntity match = matches.get(i);
            match.setRank(i + 1);
            match.setRankExplanation(append(match.getRankExplanation(), reason));
        }
        return matches;
    }

    private String append(String original, String addition) {
        if (addition == null || addition.isBlank()) {
            return original == null ? "" : original;
        }
        return (original == null || original.isBlank()) ? addition : original + ", " + addition;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
