package com.opsautoagent.infrastructure.adapter.gateway.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Component
@ConditionalOnBean(VectorStore.class)
@ConditionalOnProperty(prefix = "ops.runbook.vector", name = "enabled", havingValue = "true")
public class OpsRunbookVectorIndexer implements ApplicationListener<ApplicationReadyEvent> {

    private final VectorStore vectorStore;
    private final TokenTextSplitter tokenTextSplitter;
    private final JdbcTemplate pgVectorJdbcTemplate;

    @Value("${ops.runbook.base-path:docs/dev-ops/runbook}")
    private String basePath;

    @Value("${ops.runbook.vector.rebuild-on-startup:true}")
    private boolean rebuildOnStartup;

    @Value("${ops.runbook.vector.fail-fast:false}")
    private boolean failFast;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store_openai}")
    private String vectorTableName;

    @Value("${ops.runbook.vector.index-batch-size:8}")
    private Integer indexBatchSize;

    @Value("${ops.runbook.vector.index-batch-retries:3}")
    private Integer indexBatchRetries;

    public OpsRunbookVectorIndexer(VectorStore vectorStore,
                                   TokenTextSplitter tokenTextSplitter,
                                   @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate) {
        this.vectorStore = vectorStore;
        this.tokenTextSplitter = tokenTextSplitter;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            Path runbookPath = Path.of(basePath);
            if (!Files.isDirectory(runbookPath)) {
                log.warn("Ops runbook vector index skipped, path not found: {}", runbookPath.toAbsolutePath());
                return;
            }

            if (rebuildOnStartup) {
                deleteOldRunbookVectors();
            }

            List<Document> documents = loadRunbookDocuments(runbookPath);
            if (documents.isEmpty()) {
                log.warn("Ops runbook vector index skipped, no markdown runbook found: {}", runbookPath.toAbsolutePath());
                return;
            }

            List<Document> chunks = new ArrayList<>();
            for (Document document : documents) {
                List<Document> splitChunks = tokenTextSplitter.split(document);
                for (int i = 0; i < splitChunks.size(); i++) {
                    chunks.add(enrichChunkMetadata(document, splitChunks.get(i), i, splitChunks.size()));
                }
            }
            addChunks(chunks);
            log.info("Ops runbook vector index completed. documents={}, chunks={}, path={}",
                    documents.size(), chunks.size(), runbookPath.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Ops runbook vector index failed", e);
            if (failFast) {
                throw new IllegalStateException("Ops runbook vector index failed", e);
            }
        }
    }

    private List<Document> loadRunbookDocuments(Path runbookPath) throws Exception {
        List<Document> documents = new ArrayList<>();
        try (Stream<Path> files = Files.list(runbookPath)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> addDocument(path, documents));
        }
        return documents;
    }

    private void addDocument(Path path, List<Document> documents) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String runbookId = stripExtension(path.getFileName().toString());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "ops-runbook");
            metadata.put("runbookId", runbookId);
            metadata.put("title", extractTitle(content, path));
            metadata.put("category", resolveCategory(path.getFileName().toString(), content));
            metadata.put("path", path.toString());
            documents.add(new Document("ops-runbook-" + runbookId, content, metadata));
        } catch (Exception e) {
            log.warn("Read ops runbook for vector index failed. path={}", path.toAbsolutePath(), e);
        }
    }

    private void deleteOldRunbookVectors() {
        try {
            pgVectorJdbcTemplate.update("DELETE FROM " + sanitizeTableName(vectorTableName) + " WHERE metadata ->> 'source' = 'ops-runbook'");
            log.info("Old ops runbook vectors deleted from PgVector. table={}", vectorTableName);
        } catch (Exception e) {
            log.warn("Delete old ops runbook vectors failed, continue indexing.", e);
        }
    }

    private Document enrichChunkMetadata(Document sourceDocument, Document chunk, int chunkIndex, int chunkCount) {
        Map<String, Object> metadata = new LinkedHashMap<>(sourceDocument.getMetadata());
        metadata.putAll(chunk.getMetadata());
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("chunkCount", chunkCount);
        String chunkId = sourceDocument.getId() + "-chunk-" + chunkIndex;
        metadata.put("chunkId", chunkId);
        return Document.builder()
                .id(UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString())
                .text(chunk.getText())
                .metadata(metadata)
                .build();
    }

    private void addChunks(List<Document> chunks) {
        int batchSize = Math.max(1, indexBatchSize == null ? 8 : indexBatchSize);
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(chunks.size(), start + batchSize);
            addChunkBatch(chunks.subList(start, end), start, end);
        }
    }

    private void addChunkBatch(List<Document> batch, int start, int end) {
        int maxAttempts = Math.max(1, indexBatchRetries == null ? 3 : indexBatchRetries);
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                vectorStore.add(batch);
                return;
            } catch (RuntimeException e) {
                lastException = e;
                log.warn("Add ops runbook vector batch failed. range={}..{}, attempt={}/{}",
                        start, end, attempt, maxAttempts, e);
                sleepBeforeRetry(attempt);
            }
        }
        throw lastException == null ? new IllegalStateException("Add ops runbook vector batch failed") : lastException;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(5000L, 1000L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying ops runbook vector index", e);
        }
    }

    private String sanitizeTableName(String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid pgvector table name: " + tableName);
        }
        return tableName;
    }

    private String extractTitle(String content, Path path) {
        for (String line : content.split("\\R")) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return stripExtension(path.getFileName().toString());
    }

    private String resolveCategory(String fileName, String content) {
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        if (lowerFileName.contains("connection-pool") || lowerFileName.contains("slow-sql")) {
            return "database";
        }
        if (lowerFileName.contains("redis")) {
            return "redis";
        }
        if (lowerFileName.contains("jvm")) {
            return "jvm";
        }
        if (lowerFileName.contains("rpc")) {
            return "downstream";
        }
        if (lowerFileName.contains("mq")) {
            return "mq";
        }
        if (lowerFileName.contains("gateway")) {
            return "gateway";
        }
        if (lowerFileName.contains("thread-pool")) {
            return "thread_pool";
        }
        if (lowerFileName.contains("cpu")) {
            return "system";
        }
        if (lowerFileName.contains("observability")) {
            return "observability";
        }
        if (lowerFileName.contains("sufficiency")) {
            return "policy";
        }
        if (lowerFileName.contains("500")) {
            return "application";
        }
        String text = (fileName + "\n" + content).toLowerCase(Locale.ROOT);
        if (text.contains("hikari") || text.contains("jdbc") || text.contains("database")) {
            return "database";
        }
        if (text.contains("redis")) {
            return "redis";
        }
        if (text.contains("full gc") || text.contains("outofmemory")) {
            return "jvm";
        }
        if (text.contains("dubbo") || text.contains("rpc") || text.contains("downstream")) {
            return "downstream";
        }
        if (text.contains("mq") || text.contains("kafka") || text.contains("rocketmq")) {
            return "mq";
        }
        if (text.contains("500") || text.contains("exception")) {
            return "application";
        }
        return "general";
    }

    private String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

}

