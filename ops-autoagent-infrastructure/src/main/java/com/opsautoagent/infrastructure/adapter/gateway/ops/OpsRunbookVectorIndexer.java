package com.opsautoagent.infrastructure.adapter.gateway.ops;

import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson.JSON;
import org.springframework.ai.document.Document;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Component
@ConditionalOnBean(VectorStore.class)
@ConditionalOnProperty(prefix = "ops.runbook.vector", name = "enabled", havingValue = "true")
public class OpsRunbookVectorIndexer implements ApplicationListener<ApplicationReadyEvent> {

    private final VectorStore vectorStore;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final OpsRunbookMarkdownChunker markdownChunker;

    @Value("${ops.runbook.base-path:docs/dev-ops/runbook}")
    private String basePath;

    @Value("${ops.runbook.vector.rebuild-on-startup:false}")
    private boolean rebuildOnStartup;

    @Value("${ops.runbook.vector.schema-check-on-startup:true}")
    private boolean schemaCheckOnStartup;

    @Value("${ops.runbook.vector.fail-fast:false}")
    private boolean failFast;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store_openai}")
    private String vectorTableName;

    @Value("${ops.runbook.embedding.dimensions:${spring.ai.openai.embedding.options.dimensions:1536}}")
    private Integer embeddingDimensions;

    @Value("${ops.runbook.vector.index-batch-size:8}")
    private Integer indexBatchSize;

    @Value("${ops.runbook.vector.index-batch-retries:3}")
    private Integer indexBatchRetries;

    @Value("${ops.runbook.chunk.max-chars:1800}")
    private int maxChunkChars;

    public OpsRunbookVectorIndexer(VectorStore vectorStore,
                                   @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                   OpsRunbookMarkdownChunker markdownChunker) {
        this.vectorStore = vectorStore;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.markdownChunker = markdownChunker;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            Path runbookPath = Path.of(basePath);
            if (!Files.isDirectory(runbookPath)) {
                log.warn("Ops runbook vector index skipped, path not found: {}", runbookPath.toAbsolutePath());
                return;
            }

            boolean tableRecreated = false;
            if (schemaCheckOnStartup || rebuildOnStartup) {
                tableRecreated = ensureVectorTableDimension();
            }

            if (rebuildOnStartup && !tableRecreated) {
                deleteOldRunbookVectors();
            }

            List<OpsRunbookMarkdownChunker.RunbookDocument> runbookDocuments = loadRunbookDocuments(runbookPath);
            if (runbookDocuments.isEmpty()) {
                log.warn("Ops runbook vector index skipped, no markdown runbook found: {}", runbookPath.toAbsolutePath());
                return;
            }

            List<Document> chunks = new ArrayList<>();
            for (OpsRunbookMarkdownChunker.RunbookDocument document : runbookDocuments) {
                for (OpsRunbookMarkdownChunker.RunbookChunk chunk : document.chunks()) {
                    chunks.add(toVectorDocument(document, chunk));
                }
            }
            List<Document> chunksToAdd = (rebuildOnStartup || tableRecreated) ? chunks : filterNewChunks(chunks);
            if (!chunksToAdd.isEmpty()) {
                addChunks(chunksToAdd);
            }
            writeIngestManifest(runbookPath, runbookDocuments, chunks, chunksToAdd);
            log.info("Ops runbook vector index completed. documents={}, chunks={}, indexedChunks={}, skippedChunks={}, path={}",
                    runbookDocuments.size(), chunks.size(), chunksToAdd.size(), chunks.size() - chunksToAdd.size(), runbookPath.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Ops runbook vector index failed", e);
            if (failFast) {
                throw new IllegalStateException("Ops runbook vector index failed", e);
            }
        }
    }

    private List<OpsRunbookMarkdownChunker.RunbookDocument> loadRunbookDocuments(Path runbookPath) throws Exception {
        List<OpsRunbookMarkdownChunker.RunbookDocument> documents = new ArrayList<>();
        try (Stream<Path> files = Files.list(runbookPath)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> addDocument(path, documents));
        }
        return documents;
    }

    private void addDocument(Path path, List<OpsRunbookMarkdownChunker.RunbookDocument> documents) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            documents.add(markdownChunker.parse(path, content, maxChunkChars));
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

    private boolean ensureVectorTableDimension() {
        int expectedDimensions = embeddingDimensions == null || embeddingDimensions <= 0 ? 1536 : embeddingDimensions;
        try {
            String tableName = sanitizeTableName(vectorTableName);
            Integer actualDimensions = pgVectorJdbcTemplate.queryForObject("""
                    SELECT CASE WHEN a.atttypmod > 0 THEN a.atttypmod ELSE NULL END
                    FROM pg_attribute a
                    JOIN pg_class c ON a.attrelid = c.oid
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE n.nspname = 'public'
                      AND c.relname = ?
                      AND a.attname = 'embedding'
                      AND a.attnum > 0
                      AND NOT a.attisdropped
                    """, Integer.class, tableName);
            if (actualDimensions != null && actualDimensions == expectedDimensions) {
                return false;
            }
            log.warn("PgVector runbook table dimension mismatch. table={}, actual={}, expected={}. Recreating table.",
                    vectorTableName, actualDimensions, expectedDimensions);
            recreateVectorTable(tableName, expectedDimensions);
            return true;
        } catch (Exception e) {
            log.warn("Check PgVector runbook table dimension failed, recreating table. table={}",
                    vectorTableName, e);
            recreateVectorTable(sanitizeTableName(vectorTableName), expectedDimensions);
            return true;
        }
    }

    private void recreateVectorTable(String tableName, int dimensions) {
        pgVectorJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        pgVectorJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        pgVectorJdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
        pgVectorJdbcTemplate.execute("""
                CREATE TABLE %s (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    content TEXT NOT NULL,
                    metadata JSONB,
                    embedding VECTOR(%d)
                )
                """.formatted(tableName, dimensions));
        log.info("PgVector runbook table recreated. table={}, dimensions={}", tableName, dimensions);
    }

    private Document toVectorDocument(OpsRunbookMarkdownChunker.RunbookDocument document,
                                      OpsRunbookMarkdownChunker.RunbookChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "ops-runbook");
        metadata.put("runbookId", document.runbookId());
        metadata.put("title", document.title());
        metadata.put("category", document.category());
        metadata.put("path", document.path());
        metadata.put("documentVersion", document.documentVersion());
        metadata.put("documentHash", document.documentHash());
        metadata.put("ingestPipeline", document.ingestPipeline());
        metadata.put("chunkId", chunk.chunkId());
        metadata.put("chunkIndex", chunk.chunkIndex());
        metadata.put("chunkCount", chunk.chunkCount());
        metadata.put("chunkSection", chunk.sectionTitle());
        metadata.put("chunkHash", chunk.chunkHash());
        return Document.builder()
                .id(UUID.nameUUIDFromBytes(chunk.chunkId().getBytes(StandardCharsets.UTF_8)).toString())
                .text(chunk.content())
                .metadata(metadata)
                .build();
    }

    private List<Document> filterNewChunks(List<Document> chunks) {
        List<Document> result = new ArrayList<>();
        for (Document chunk : chunks) {
            if (!chunkAlreadyIndexed(chunk)) {
                result.add(chunk);
            }
        }
        return result;
    }

    private boolean chunkAlreadyIndexed(Document chunk) {
        try {
            Map<String, Object> metadata = chunk.getMetadata();
            String chunkId = String.valueOf(metadata.getOrDefault("chunkId", ""));
            String documentHash = String.valueOf(metadata.getOrDefault("documentHash", ""));
            String chunkHash = String.valueOf(metadata.getOrDefault("chunkHash", ""));
            Integer count = pgVectorJdbcTemplate.queryForObject("""
                    SELECT COUNT(1)
                    FROM %s
                    WHERE metadata ->> 'source' = 'ops-runbook'
                      AND metadata ->> 'chunkId' = ?
                      AND metadata ->> 'documentHash' = ?
                      AND metadata ->> 'chunkHash' = ?
                    """.formatted(sanitizeTableName(vectorTableName)), Integer.class, chunkId, documentHash, chunkHash);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("Check runbook chunk version existence failed, will index chunk. id={}", chunk.getId(), e);
            return false;
        }
    }

    private void writeIngestManifest(Path runbookPath,
                                     List<OpsRunbookMarkdownChunker.RunbookDocument> documents,
                                     List<Document> chunks,
                                     List<Document> chunksToAdd) {
        try {
            Path dir = Path.of("data", "runbook-rag-index");
            Files.createDirectories(dir);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("basePath", runbookPath.toAbsolutePath().toString());
            manifest.put("rebuildOnStartup", rebuildOnStartup);
            manifest.put("documents", documents.size());
            manifest.put("chunksTotal", chunks.size());
            manifest.put("chunksIndexed", chunksToAdd.size());
            manifest.put("chunksSkippedExistingVersion", chunks.size() - chunksToAdd.size());
            manifest.put("vectorTableName", vectorTableName);
            manifest.put("ingestPipeline", "markdown-section-chunker-v2");
            manifest.put("maxChunkChars", maxChunkChars);
            manifest.put("runbooks", documents.stream().map(this::documentManifest).toList());
            Files.writeString(dir.resolve("manifest.json"), JSON.toJSONString(manifest, true), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Write ops runbook index manifest failed.", e);
        }
    }

    private Map<String, Object> documentManifest(OpsRunbookMarkdownChunker.RunbookDocument document) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("runbookId", document.runbookId());
        item.put("title", document.title());
        item.put("path", document.path());
        item.put("documentVersion", document.documentVersion());
        item.put("documentHash", document.documentHash());
        item.put("category", document.category());
        item.put("chunkCount", document.chunks().size());
        return item;
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

}

