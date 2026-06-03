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
    private final RunbookCrossEncoderReranker crossEncoderReranker;

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

    public PgVectorOpsRunbookGateway(VectorStore vectorStore,
                                     FileOpsRunbookGateway fileOpsRunbookGateway,
                                     RunbookCrossEncoderReranker crossEncoderReranker) {
        this.vectorStore = vectorStore;
        this.fileOpsRunbookGateway = fileOpsRunbookGateway;
        this.crossEncoderReranker = crossEncoderReranker;
    }

    @Override
    public List<RunbookMatchEntity> search(IncidentCommandEntity command,
                                           List<RootCauseCandidateEntity> rootCauseCandidates,
                                           int topK) {
        try {
            String query = buildQuery(command, rootCauseCandidates);
            List<RunbookMatchEntity> vectorMatches = vectorSearch(query, topK);
            List<RunbookMatchEntity> keywordMatches = hybridEnabled
                    ? fileOpsRunbookGateway.searchCandidates(command, rootCauseCandidates, overFetchTopK(topK))
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
                    ? fileOpsRunbookGateway.searchSignalCandidates(command, evidenceSignals, overFetchTopK(topK))
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
        int vectorScore = document.getScore() == null ? 0 : Math.max(1, (int) Math.round(document.getScore() * 100));
        return RunbookMatchEntity.builder()
                .runbookId(metadata(document, "runbookId", document.getId()))
                .title(metadata(document, "title", "运维知识库 Runbook"))
                .category(metadata(document, "category", "general"))
                .score(vectorScore)
                .vectorScore(vectorScore)
                .keywordScore(0)
                .bm25Score(0)
                .hybridScore(vectorScore)
                .lexicalBoostScore(0)
                .crossEncoderScore(0)
                .retrievalMode("VECTOR")
                .path("pgvector:" + metadata(document, "path", "vector_store_openai"))
                .summary(abbreviate(document.getText(), 600))
                .content(buildParentChildContent(document))
                .chunkId(metadata(document, "chunkId", document.getId()))
                .chunkIndex(parseIntMetadata(document, "chunkIndex"))
                .chunkCount(parseIntMetadata(document, "chunkCount"))
                .documentVersion(metadata(document, "documentVersion", "unknown"))
                .documentHash(metadata(document, "documentHash", ""))
                .rankExplanation("vectorScore=" + vectorScore + ", source=pgvector")
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
        Map<String, HybridRank> rankByChunk = new LinkedHashMap<>();
        addRankScores(rankByChunk, vectorMatches, vectorWeight, query);
        addRankScores(rankByChunk, keywordMatches, keywordWeight, query);
        List<RunbookMatchEntity> ranked = rankByChunk.values().stream()
                .sorted((left, right) -> Double.compare(right.score, left.score))
                .map(HybridRank::toMatch)
                .limit(overFetchTopK(topK))
                .toList();
        return crossEncoderReranker.rerank(query, ranked, topK);
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
            String key = firstNonBlank(match.getChunkId(), match.getDocumentHash(), match.getRunbookId());
            if (key.isBlank()) {
                continue;
            }
            double rrfScore = weight / (rrfK + i + 1);
            HybridRank rank = rankByRunbook.computeIfAbsent(key, ignored -> new HybridRank(match));
            rank.score += rrfScore;
            rank.vectorScore = Math.max(rank.vectorScore, "VECTOR".equals(match.getRetrievalMode()) ? score(match) : score(match.getVectorScore()));
            boolean bm25Match = "BM25_CHUNK".equals(match.getRetrievalMode()) || "KEYWORD".equals(match.getRetrievalMode());
            rank.keywordScore = Math.max(rank.keywordScore, bm25Match ? score(match) : score(match.getKeywordScore()));
            rank.bm25Score = Math.max(rank.bm25Score, score(match.getBm25Score()));
            rank.lexicalBoostScore = 0;
            rank.sources.add(match.getRetrievalMode() == null ? "UNKNOWN" : match.getRetrievalMode());
            if (score(match) > score(rank.match)) {
                rank.match = match;
            }
        }
    }

    private int score(RunbookMatchEntity match) {
        return match == null || match.getScore() == null ? 0 : match.getScore();
    }

    private int score(Integer value) {
        return value == null ? 0 : value;
    }

    private String buildParentChildContent(Document document) {
        String chunkText = abbreviate(document.getText(), 1200);
        return String.join("\n\n",
                "命中片段:\n" + chunkText,
                "片段元数据: runbookId=" + metadata(document, "runbookId", "")
                        + ", chunkId=" + metadata(document, "chunkId", "")
                        + ", chunkIndex=" + metadata(document, "chunkIndex", "")
                        + ", chunkCount=" + metadata(document, "chunkCount", "")
                        + ", documentVersion=" + metadata(document, "documentVersion", "")
                        + ", path=" + metadata(document, "path", ""));
    }

    private int overFetchTopK(int topK) {
        return Math.max(8, Math.max(1, topK) * 4);
    }

    private String metadata(Document document, String key, String defaultValue) {
        Object value = document.getMetadata() == null ? null : document.getMetadata().get(key);
        return value == null ? defaultValue : value.toString();
    }

    private Integer parseIntMetadata(Document document, String key) {
        Object value = document.getMetadata() == null ? null : document.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
        private int vectorScore;
        private int keywordScore;
        private int bm25Score;
        private int lexicalBoostScore;
        private final java.util.Set<String> sources = new java.util.LinkedHashSet<>();

        private HybridRank(RunbookMatchEntity match) {
            this.match = match;
        }

        private RunbookMatchEntity toMatch() {
            int currentScore = match.getScore() == null ? 0 : match.getScore();
            int hybridScore = Math.min(100, Math.max(currentScore, (int) Math.round(score * 1000)));
            match.setScore(hybridScore);
            match.setHybridScore(hybridScore);
            match.setVectorScore(vectorScore);
            match.setKeywordScore(keywordScore);
            match.setBm25Score(bm25Score);
            match.setLexicalBoostScore(lexicalBoostScore);
            match.setCrossEncoderScore(match.getCrossEncoderScore() == null ? 0 : match.getCrossEncoderScore());
            match.setRetrievalMode(sources.size() > 1 ? "HYBRID" : sources.stream().findFirst().orElse(match.getRetrievalMode()));
            match.setRankExplanation("retrievalMode=" + match.getRetrievalMode()
                    + ", vectorScore=" + vectorScore
                    + ", bm25Score=" + bm25Score
                    + ", lexicalBoost=" + lexicalBoostScore
                    + ", rrfHybridScore=" + String.format(java.util.Locale.ROOT, "%.4f", score));
            return match;
        }
    }

}

