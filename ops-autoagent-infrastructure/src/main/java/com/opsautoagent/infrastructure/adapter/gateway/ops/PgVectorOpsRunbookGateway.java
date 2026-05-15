package com.opsautoagent.infrastructure.adapter.gateway.ops;

import com.opsautoagent.domain.ops.adapter.gateway.IOpsRunbookGateway;
import com.opsautoagent.domain.ops.model.entity.EvidenceSignalEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.RootCauseCandidateEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Primary
@Component
@ConditionalOnBean(VectorStore.class)
@ConditionalOnProperty(prefix = "ops.runbook.vector", name = "search-enabled", havingValue = "true")
public class PgVectorOpsRunbookGateway implements IOpsRunbookGateway {

    private final VectorStore vectorStore;
    private final FileOpsRunbookGateway fileOpsRunbookGateway;

    @Value("${ops.runbook.vector.fallback-to-file:true}")
    private boolean fallbackToFile;

    @Value("${ops.runbook.hybrid.enabled:true}")
    private boolean hybridEnabled;

    @Value("${ops.runbook.hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${ops.runbook.hybrid.vector-weight:1.0}")
    private double vectorWeight;

    @Value("${ops.runbook.hybrid.keyword-weight:1.3}")
    private double keywordWeight;

    public PgVectorOpsRunbookGateway(VectorStore vectorStore, FileOpsRunbookGateway fileOpsRunbookGateway) {
        this.vectorStore = vectorStore;
        this.fileOpsRunbookGateway = fileOpsRunbookGateway;
    }

    @Override
    public List<RunbookMatchEntity> search(IncidentCommandEntity command,
                                           List<RootCauseCandidateEntity> rootCauseCandidates,
                                           int topK) {
        try {
            String query = buildQuery(command, rootCauseCandidates);
            List<RunbookMatchEntity> vectorMatches = vectorSearch(query, topK);
            List<RunbookMatchEntity> keywordMatches = hybridEnabled
                    ? fileOpsRunbookGateway.search(command, rootCauseCandidates, overFetchTopK(topK))
                    : List.of();
            List<RunbookMatchEntity> matches = mergeHybridMatches(vectorMatches, keywordMatches, topK, query);
            if (!matches.isEmpty()) {
                return matches;
            }
            log.warn("PgVector runbook search returned empty result. service={}, fallbackToFile={}",
                    command.getServiceName(), fallbackToFile);
        } catch (Exception e) {
            log.warn("PgVector runbook search failed. service={}, fallbackToFile={}",
                    command.getServiceName(), fallbackToFile, e);
        }
        return fallbackToFile ? fileOpsRunbookGateway.search(command, rootCauseCandidates, topK) : List.of();
    }

    @Override
    public List<RunbookMatchEntity> searchByEvidenceSignals(IncidentCommandEntity command,
                                                            List<EvidenceSignalEntity> evidenceSignals,
                                                            int topK) {
        try {
            String query = buildSignalQuery(command, evidenceSignals);
            List<RunbookMatchEntity> vectorMatches = vectorSearch(query, topK);
            List<RunbookMatchEntity> keywordMatches = hybridEnabled
                    ? fileOpsRunbookGateway.searchByEvidenceSignals(command, evidenceSignals, overFetchTopK(topK))
                    : List.of();
            List<RunbookMatchEntity> matches = mergeHybridMatches(vectorMatches, keywordMatches, topK, query);
            if (!matches.isEmpty()) {
                return matches;
            }
            log.warn("PgVector runbook signal search returned empty result. service={}, fallbackToFile={}",
                    command.getServiceName(), fallbackToFile);
        } catch (Exception e) {
            log.warn("PgVector runbook signal search failed. service={}, fallbackToFile={}",
                    command.getServiceName(), fallbackToFile, e);
        }
        return fallbackToFile ? fileOpsRunbookGateway.searchByEvidenceSignals(command, evidenceSignals, topK) : List.of();
    }

    private String buildQuery(IncidentCommandEntity command, List<RootCauseCandidateEntity> rootCauseCandidates) {
        String candidates = rootCauseCandidates == null ? "" : rootCauseCandidates.stream()
                .map(candidate -> String.join(" ",
                        safe(candidate.getCategory()),
                        safe(candidate.getCause()),
                        safe(candidate.getReasoning())))
                .collect(Collectors.joining("\n"));
        return String.join("\n",
                safe(command.getServiceName()),
                safe(command.getProblem()),
                candidates);
    }

    private String buildSignalQuery(IncidentCommandEntity command, List<EvidenceSignalEntity> evidenceSignals) {
        String signals = evidenceSignals == null ? "" : evidenceSignals.stream()
                .map(signal -> String.join(" ",
                        safe(signal.getSource()),
                        safe(signal.getEvidenceType()),
                        safe(signal.getName()),
                        safe(signal.getStatus()),
                        safe(signal.getSummary()),
                        safe(signal.getRawEvidence())))
                .collect(Collectors.joining("\n"));
        return String.join("\n",
                safe(command.getServiceName()),
                safe(command.getProblem()),
                signals);
    }

    private RunbookMatchEntity toRunbookMatch(Document document) {
        return RunbookMatchEntity.builder()
                .runbookId(metadata(document, "runbookId", document.getId()))
                .title(metadata(document, "title", "运维知识库 Runbook"))
                .category(metadata(document, "category", "general"))
                .score(document.getScore() == null ? 0 : Math.max(1, (int) Math.round(document.getScore() * 100)))
                .path("pgvector:" + metadata(document, "path", "vector_store_openai"))
                .summary(abbreviate(document.getText(), 600))
                .content(buildParentChildContent(document))
                .build();
    }

    private List<RunbookMatchEntity> vectorSearch(String query, int topK) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(overFetchTopK(topK))
                .build());
        return documents.stream()
                .filter(Objects::nonNull)
                .map(this::toRunbookMatch)
                .toList();
    }

    private List<RunbookMatchEntity> mergeHybridMatches(List<RunbookMatchEntity> vectorMatches,
                                                        List<RunbookMatchEntity> keywordMatches,
                                                        int topK,
                                                        String query) {
        Map<String, HybridRank> rankByRunbook = new LinkedHashMap<>();
        addRankScores(rankByRunbook, vectorMatches, vectorWeight, query);
        addRankScores(rankByRunbook, keywordMatches, keywordWeight, query);
        return rankByRunbook.values().stream()
                .sorted((left, right) -> Double.compare(right.score, left.score))
                .map(HybridRank::toMatch)
                .limit(Math.max(1, topK))
                .toList();
    }

    private void addRankScores(Map<String, HybridRank> rankByRunbook,
                               List<RunbookMatchEntity> matches,
                               double weight,
                               String query) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        for (int i = 0; i < matches.size(); i++) {
            RunbookMatchEntity match = matches.get(i);
            String key = safe(match.getRunbookId());
            if (key.isBlank()) {
                continue;
            }
            double rrfScore = weight / (rrfK + i + 1);
            double boostScore = lexicalBoost(key, query) / 100.0;
            HybridRank rank = rankByRunbook.computeIfAbsent(key, ignored -> new HybridRank(match));
            rank.score += rrfScore + boostScore;
            if (score(match) > score(rank.match)) {
                rank.match = match;
            }
        }
    }

    private int lexicalBoost(String runbookId, String query) {
        String q = safe(query).toLowerCase();
        if (containsAny(q, "hikari", "connection is not available", "connection pool", "jdbc connection", "pending connection", "acquire time", "maximum pool")) {
            return "database-connection-pool".equals(runbookId) ? 30 : 0;
        }
        if (containsAny(q, "slow sql", "db span", "lock wait", "deadlock", "rows_examined", "explain", "query timeout")) {
            return "slow-sql-db-span".equals(runbookId) ? 30 : 0;
        }
        if (containsAny(q, "nullpointer", "illegalargument", "business exception", "stacktrace", "application exception", "http 500")) {
            return "http-500-error".equals(runbookId) ? 28 : 0;
        }
        if (containsAny(q, "gateway", "upstream", "no healthy upstream", "route", "502", "503", "504")) {
            return "gateway-http-5xx".equals(runbookId) ? 30 : 0;
        }
        if (containsAny(q, "no_anomaly", "no anomaly", "missing trace", "no trace", "no log", "sampling", "observability", "blind spot", "coverage gap")) {
            return "observability-gap".equals(runbookId) ? 35 : 0;
        }
        if (containsAny(q, "sufficiency", "negative evidence", "need_more_evidence", "probable_root_cause", "root_cause_confirmed", "direct evidence")) {
            return "evidence-sufficiency-policy".equals(runbookId) ? 35 : 0;
        }
        if (containsAny(q, "redis", "lettuce", "jedis", "cache", "hot key", "big key")) {
            return "redis-timeout".equals(runbookId) ? 30 : 0;
        }
        if (containsAny(q, "rpc", "dubbo", "feign", "downstream", "webclient", "resttemplate")) {
            return "rpc-timeout".equals(runbookId) ? 30 : 0;
        }
        if (containsAny(q, "full gc", "outofmemory", "old gen", "gc pause", "heap")) {
            return "jvm-full-gc".equals(runbookId) ? 30 : 0;
        }
        if (containsAny(q, "mq", "kafka", "rocketmq", "rabbitmq", "consumer lag", "dead letter")) {
            return "mq-backlog".equals(runbookId) ? 30 : 0;
        }
        if (containsAny(q, "thread pool", "tomcat_threads_busy", "rejectedexecution", "executor_active", "queue size")) {
            return "thread-pool-saturation".equals(runbookId) ? 30 : 0;
        }
        if (containsAny(q, "cpu", "load average", "throttling", "hot thread", "process_cpu")) {
            return "cpu-saturation".equals(runbookId) ? 30 : 0;
        }
        return 0;
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int score(RunbookMatchEntity match) {
        return match == null || match.getScore() == null ? 0 : match.getScore();
    }

    private String buildParentChildContent(Document document) {
        String chunkText = abbreviate(document.getText(), 1200);
        String parentContext = readParentContext(metadata(document, "path", ""));
        if (parentContext.isBlank()) {
            return chunkText;
        }
        return String.join("\n\n",
                "命中片段:\n" + chunkText,
                "父文档上下文:\n" + parentContext);
    }

    private String readParentContext(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path parentPath = Path.of(path);
            if (!Files.isRegularFile(parentPath)) {
                return "";
            }
            String content = Files.readString(parentPath, StandardCharsets.UTF_8);
            return abbreviate(content, 2800);
        } catch (Exception e) {
            log.debug("Read parent runbook context failed. path={}", path, e);
            return "";
        }
    }

    private int overFetchTopK(int topK) {
        return Math.max(8, Math.max(1, topK) * 4);
    }

    private String metadata(Document document, String key, String defaultValue) {
        Object value = document.getMetadata() == null ? null : document.getMetadata().get(key);
        return value == null ? defaultValue : value.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private static class HybridRank {
        private RunbookMatchEntity match;
        private double score;

        private HybridRank(RunbookMatchEntity match) {
            this.match = match;
        }

        private RunbookMatchEntity toMatch() {
            int currentScore = match.getScore() == null ? 0 : match.getScore();
            match.setScore(Math.min(100, Math.max(currentScore, (int) Math.round(score * 1000))));
            return match;
        }
    }

}

