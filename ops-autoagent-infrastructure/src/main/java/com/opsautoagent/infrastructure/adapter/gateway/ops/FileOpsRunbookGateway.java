package com.opsautoagent.infrastructure.adapter.gateway.ops;

import com.opsautoagent.domain.ops.agent.governance.OpsToolProtocolResolver;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsRunbookGateway;
import com.opsautoagent.domain.ops.adapter.repository.IOpsGovernanceRepository;
import com.opsautoagent.domain.ops.model.entity.EvidenceSignalEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsToolCallLogEntity;
import com.opsautoagent.domain.ops.model.entity.RootCauseCandidateEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import com.opsautoagent.domain.ops.service.OpsSensitiveDataMasker;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class FileOpsRunbookGateway implements IOpsRunbookGateway {

    @Value("${ops.runbook.base-path:docs/dev-ops/runbook}")
    private String basePath;

    @Resource
    private IOpsGovernanceRepository governanceRepository;

    @Resource
    private OpsSensitiveDataMasker sensitiveDataMasker;

    @Resource
    private OpsRunbookMarkdownChunker markdownChunker;

    @Resource
    private RunbookCrossEncoderReranker crossEncoderReranker;

    @Value("${ops.runbook.chunk.max-chars:1800}")
    private int maxChunkChars;

    @Override
    public List<RunbookMatchEntity> search(IncidentCommandEntity command,
                                           List<RootCauseCandidateEntity> rootCauseCandidates,
                                           int topK) {
        return search(command, rootCauseCandidates, topK, true);
    }

    List<RunbookMatchEntity> searchCandidates(IncidentCommandEntity command,
                                              List<RootCauseCandidateEntity> rootCauseCandidates,
                                              int topK) {
        return search(command, rootCauseCandidates, topK, false);
    }

    private List<RunbookMatchEntity> search(IncidentCommandEntity command,
                                            List<RootCauseCandidateEntity> rootCauseCandidates,
                                            int topK,
                                            boolean applyRerank) {
        long start = System.currentTimeMillis();
        Path runbookPath = Path.of(basePath);
        if (!Files.exists(runbookPath) || !Files.isDirectory(runbookPath)) {
            log.warn("Ops runbook path does not exist: {}", runbookPath.toAbsolutePath());
            saveToolCallLog(command, runbookPath.toString(), "runbook path missing", List.of(),
                    System.currentTimeMillis() - start, false, "runbook path does not exist");
            return List.of();
        }

        Set<String> queryTokens = buildQueryTokens(command, rootCauseCandidates);
        List<ChunkDocument> corpus = new ArrayList<>();

        try (Stream<Path> files = Files.list(runbookPath)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> addChunkDocuments(path, corpus));
        } catch (IOException e) {
            log.warn("Search ops runbooks failed. path={}", runbookPath.toAbsolutePath(), e);
            saveToolCallLog(command, runbookPath.toString(), queryTokens.toString(), List.of(),
                    System.currentTimeMillis() - start, false, e.getMessage());
            return List.of();
        }
        List<RunbookMatchEntity> matches = bm25Search(corpus, queryTokens);

        List<RunbookMatchEntity> candidates = matches.stream()
                .filter(match -> match.getScore() > 0)
                .sorted(Comparator.comparing(RunbookMatchEntity::getScore).reversed())
                .limit(Math.max(1, topK) * 5L)
                .toList();
        List<RunbookMatchEntity> result = applyRerank
                ? crossEncoderReranker.rerank(String.join(" ", queryTokens), candidates, topK)
                : candidates.stream().limit(Math.max(1, topK)).toList();
        assignRanks(result);
        saveToolCallLog(command, runbookPath.toString(), queryTokens.toString(), result,
                System.currentTimeMillis() - start, true, null);
        return result;
    }

    @Override
    public List<RunbookMatchEntity> searchByEvidenceSignals(IncidentCommandEntity command,
                                                            List<EvidenceSignalEntity> evidenceSignals,
                                                            int topK) {
        return searchByEvidenceSignals(command, evidenceSignals, topK, true);
    }

    List<RunbookMatchEntity> searchSignalCandidates(IncidentCommandEntity command,
                                                    List<EvidenceSignalEntity> evidenceSignals,
                                                    int topK) {
        return searchByEvidenceSignals(command, evidenceSignals, topK, false);
    }

    private List<RunbookMatchEntity> searchByEvidenceSignals(IncidentCommandEntity command,
                                                             List<EvidenceSignalEntity> evidenceSignals,
                                                             int topK,
                                                             boolean applyRerank) {
        long start = System.currentTimeMillis();
        Path runbookPath = Path.of(basePath);
        if (!Files.exists(runbookPath) || !Files.isDirectory(runbookPath)) {
            log.warn("Ops runbook path does not exist: {}", runbookPath.toAbsolutePath());
            saveToolCallLog(command, runbookPath.toString(), "runbook path missing", List.of(),
                    System.currentTimeMillis() - start, false, "runbook path does not exist");
            return List.of();
        }

        Set<String> queryTokens = buildSignalQueryTokens(command, evidenceSignals);
        List<ChunkDocument> corpus = new ArrayList<>();

        try (Stream<Path> files = Files.list(runbookPath)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> addChunkDocuments(path, corpus));
        } catch (IOException e) {
            log.warn("Search ops runbooks by evidence signals failed. path={}", runbookPath.toAbsolutePath(), e);
            saveToolCallLog(command, runbookPath.toString(), queryTokens.toString(), List.of(),
                    System.currentTimeMillis() - start, false, e.getMessage());
            return List.of();
        }
        List<RunbookMatchEntity> matches = bm25Search(corpus, queryTokens);

        List<RunbookMatchEntity> candidates = matches.stream()
                .filter(match -> match.getScore() > 0)
                .sorted(Comparator.comparing(RunbookMatchEntity::getScore).reversed())
                .limit(Math.max(1, topK) * 5L)
                .toList();
        List<RunbookMatchEntity> result = applyRerank
                ? crossEncoderReranker.rerank(String.join(" ", queryTokens), candidates, topK)
                : candidates.stream().limit(Math.max(1, topK)).toList();
        assignRanks(result);
        saveToolCallLog(command, runbookPath.toString(), queryTokens.toString(), result,
                System.currentTimeMillis() - start, true, null);
        return result;
    }

    private void addChunkDocuments(Path path, List<ChunkDocument> corpus) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            OpsRunbookMarkdownChunker.RunbookDocument document = markdownChunker.parse(path, content, maxChunkChars);
            for (OpsRunbookMarkdownChunker.RunbookChunk chunk : document.chunks()) {
                String weightedText = String.join("\n",
                        document.runbookId(),
                        document.title(),
                        document.category(),
                        chunk.sectionTitle(),
                        chunk.content());
                corpus.add(new ChunkDocument(document, chunk, termFrequencies(tokenize(weightedText))));
            }
        } catch (IOException e) {
            log.warn("Read ops runbook failed. path={}", path.toAbsolutePath(), e);
        }
    }

    private List<RunbookMatchEntity> bm25Search(List<ChunkDocument> corpus, Set<String> queryTokens) {
        if (corpus == null || corpus.isEmpty() || queryTokens == null || queryTokens.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (ChunkDocument doc : corpus) {
            for (String term : doc.termFrequency().keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        double avgDocLength = corpus.stream()
                .mapToInt(doc -> doc.termFrequency().values().stream().mapToInt(Integer::intValue).sum())
                .average()
                .orElse(1D);
        int corpusSize = corpus.size();
        List<RunbookMatchEntity> matches = new ArrayList<>();
        for (ChunkDocument doc : corpus) {
            Bm25Score score = bm25(doc, queryTokens, documentFrequency, corpusSize, avgDocLength);
            if (score.score() <= 0D) {
                continue;
            }
            int normalized = Math.min(100, Math.max(1, (int) Math.round(score.score() * 12)));
            matches.add(RunbookMatchEntity.builder()
                    .runbookId(doc.document().runbookId())
                    .title(doc.document().title() + " / " + doc.chunk().sectionTitle())
                    .category(doc.document().category())
                    .score(normalized)
                    .keywordScore(normalized)
                    .bm25Score(normalized)
                    .hybridScore(normalized)
                    .retrievalMode("BM25_CHUNK")
                    .lexicalBoostScore(0)
                    .rankExplanation("bm25Score=" + normalized
                            + ", rawBm25=" + String.format(Locale.ROOT, "%.4f", score.score())
                            + ", section=" + doc.chunk().sectionTitle()
                            + ", hitTerms=" + score.hitTerms().stream().limit(12).toList())
                    .path(doc.document().path())
                    .summary(extractSummary(doc.chunk().content()))
                    .content(abbreviate(doc.chunk().content(), 1800))
                    .chunkId(doc.chunk().chunkId())
                    .chunkIndex(doc.chunk().chunkIndex())
                    .chunkCount(doc.chunk().chunkCount())
                    .documentHash(doc.document().documentHash())
                    .documentVersion(doc.document().documentVersion())
                    .build());
        }
        return matches.stream()
                .sorted(Comparator.comparing(RunbookMatchEntity::getBm25Score,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private Bm25Score bm25(ChunkDocument doc,
                           Set<String> queryTerms,
                           Map<String, Integer> documentFrequency,
                           int corpusSize,
                           double avgDocLength) {
        double k1 = 1.5D;
        double b = 0.75D;
        int docLength = doc.termFrequency().values().stream().mapToInt(Integer::intValue).sum();
        double score = 0D;
        List<String> hitTerms = new ArrayList<>();
        for (String term : queryTerms) {
            int tf = doc.termFrequency().getOrDefault(term, 0);
            if (tf <= 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1D + (corpusSize - df + 0.5D) / (df + 0.5D));
            double denominator = tf + k1 * (1D - b + b * docLength / Math.max(1D, avgDocLength));
            score += idf * (tf * (k1 + 1D)) / denominator;
            hitTerms.add(term);
        }
        return new Bm25Score(score, hitTerms);
    }

    private Map<String, Integer> termFrequencies(List<String> tokens) {
        return tokens.stream().collect(Collectors.toMap(token -> token, ignored -> 1, Integer::sum));
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\p{IsHan}]+", " ");
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private Set<String> buildQueryTokens(IncidentCommandEntity command,
                                         List<RootCauseCandidateEntity> rootCauseCandidates) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, command.getServiceName());
        addTokens(tokens, command.getProblem());
        if (rootCauseCandidates != null) {
            rootCauseCandidates.forEach(candidate -> {
                addTokens(tokens, candidate.getCategory());
                addTokens(tokens, candidate.getCause());
                addTokens(tokens, candidate.getReasoning());
            });
        }
        return tokens;
    }

    private Set<String> buildSignalQueryTokens(IncidentCommandEntity command,
                                               List<EvidenceSignalEntity> evidenceSignals) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, command.getServiceName());
        addTokens(tokens, command.getProblem());
        if (evidenceSignals != null) {
            evidenceSignals.forEach(signal -> {
                addTokens(tokens, signal.getSource());
                addTokens(tokens, signal.getEvidenceType());
                addTokens(tokens, signal.getName());
                addTokens(tokens, signal.getStatus());
                addTokens(tokens, signal.getSummary());
                addTokens(tokens, signal.getRawEvidence());
            });
        }
        return tokens;
    }

    private void addTokens(Set<String> tokens, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\p{IsHan}]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
    }

    private String extractSummary(String content) {
        StringBuilder summary = new StringBuilder();
        for (String line : content.split("\\R")) {
            String trimLine = line.trim();
            if (trimLine.isEmpty() || trimLine.startsWith("#") || "## keywords".equalsIgnoreCase(trimLine)) {
                continue;
            }
            summary.append(trimLine).append("\n");
            if (summary.length() > 500) {
                break;
            }
        }
        return abbreviate(summary.toString().trim(), 600);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private void assignRanks(List<RunbookMatchEntity> result) {
        if (result == null) {
            return;
        }
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setRank(i + 1);
        }
    }

    private void saveToolCallLog(IncidentCommandEntity command,
                                 String target,
                                 String requestSummary,
                                 List<RunbookMatchEntity> responseSummary,
                                 long costMillis,
                                 boolean success,
                                 String errorMessage) {
        try {
            governanceRepository.saveToolCallLog(OpsToolCallLogEntity.builder()
                    .callId("tool-" + UUID.randomUUID())
                    .sessionId(command.getSessionId())
                    .diagnosisId(command.getDiagnosisId())
                    .toolName("runbook")
                    .logicalToolName(OpsToolProtocolResolver.logicalToolNameOf("runbook", target))
                    .protocol(OpsToolProtocolResolver.protocolOf("runbook", target))
                    .governanceDecision(success ? "SUCCESS" : "FAILED")
                    .target(abbreviate(mask(target), 256))
                    .requestSummary(abbreviate(mask(requestSummary), 6000))
                    .responseSummary(abbreviate(mask(JSON.toJSONString(responseSummary)), 6000))
                    .statusCode(null)
                    .costMillis(costMillis)
                    .success(Boolean.toString(success))
                    .errorMessage(abbreviate(mask(errorMessage), 2000))
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Save runbook tool call log failed. sessionId={}", command.getSessionId(), e);
        }
    }

    private String mask(String value) {
        return sensitiveDataMasker.mask(value);
    }

    private record ChunkDocument(OpsRunbookMarkdownChunker.RunbookDocument document,
                                 OpsRunbookMarkdownChunker.RunbookChunk chunk,
                                 Map<String, Integer> termFrequency) {
    }

    private record Bm25Score(double score, List<String> hitTerms) {
    }

}

