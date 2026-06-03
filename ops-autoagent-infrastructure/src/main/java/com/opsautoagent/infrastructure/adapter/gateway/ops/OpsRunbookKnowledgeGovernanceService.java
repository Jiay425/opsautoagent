package com.opsautoagent.infrastructure.adapter.gateway.ops;

import com.opsautoagent.domain.ops.adapter.gateway.IOpsRunbookKnowledgeGovernanceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

@Component
public class OpsRunbookKnowledgeGovernanceService implements IOpsRunbookKnowledgeGovernanceService {

    @Value("${ops.runbook.base-path:docs/dev-ops/runbook}")
    private String basePath;

    @Value("${ops.runbook.chunk.max-chars:1800}")
    private int maxChunkChars;

    @Resource
    private OpsRunbookMarkdownChunker markdownChunker;

    @Override
    public Map<String, Object> summarizeCorpus() {
        Path runbookPath = Path.of(basePath);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("basePath", runbookPath.toAbsolutePath().toString());
        summary.put("chunkPipeline", "markdown-section-chunker-v2");
        summary.put("maxChunkChars", maxChunkChars);
        if (!Files.isDirectory(runbookPath)) {
            summary.put("status", "RUNBOOK_PATH_MISSING");
            summary.put("documents", 0);
            summary.put("chunks", 0);
            return summary;
        }

        List<Map<String, Object>> documents = new ArrayList<>();
        Map<String, Integer> categoryDistribution = new TreeMap<>();
        int totalChunks = 0;
        long totalBytes = 0L;

        try (Stream<Path> files = Files.list(runbookPath)) {
            for (Path path : files.filter(item -> item.getFileName().toString().endsWith(".md")).sorted().toList()) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                OpsRunbookMarkdownChunker.RunbookDocument document = markdownChunker.parse(path, content, maxChunkChars);
                totalChunks += document.chunks().size();
                totalBytes += content.getBytes(StandardCharsets.UTF_8).length;
                categoryDistribution.merge(document.category(), 1, Integer::sum);
                documents.add(documentSummary(document, content));
            }
        } catch (Exception e) {
            summary.put("status", "READ_FAILED");
            summary.put("error", e.getMessage());
            return summary;
        }

        summary.put("status", "READY");
        summary.put("documents", documents.size());
        summary.put("chunks", totalChunks);
        summary.put("totalBytes", totalBytes);
        summary.put("categoryDistribution", categoryDistribution);
        summary.put("thinDocuments", documents.stream()
                .filter(item -> ((Number) item.get("chunks")).intValue() < 3
                        || ((Number) item.get("bytes")).longValue() < 2500L)
                .toList());
        summary.put("documentVersions", documents);
        return summary;
    }

    private Map<String, Object> documentSummary(OpsRunbookMarkdownChunker.RunbookDocument document, String content) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("runbookId", document.runbookId());
        item.put("title", document.title());
        item.put("category", document.category());
        item.put("path", document.path());
        item.put("documentVersion", document.documentVersion());
        item.put("documentHash", document.documentHash());
        item.put("chunks", document.chunks().size());
        item.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
        item.put("sections", document.chunks().stream()
                .map(chunk -> Map.of(
                        "chunkId", chunk.chunkId(),
                        "chunkIndex", chunk.chunkIndex(),
                        "sectionTitle", chunk.sectionTitle(),
                        "chunkHash", chunk.chunkHash(),
                        "chars", chunk.content().length()))
                .toList());
        return item;
    }
}
