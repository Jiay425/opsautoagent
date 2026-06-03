package com.opsautoagent.infrastructure.adapter.gateway.ops;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class OpsRunbookMarkdownChunker {

    private static final String PIPELINE = "markdown-section-chunker-v2";

    public RunbookDocument parse(Path path, String content, int maxChunkChars) {
        String runbookId = stripExtension(path.getFileName().toString());
        String documentHash = sha256(content);
        String documentVersion = documentHash.substring(0, 12);
        List<RunbookChunk> chunks = split(runbookId, documentVersion, content, Math.max(800, maxChunkChars));
        return new RunbookDocument(
                runbookId,
                extractTitle(content, path),
                resolveCategory(path.getFileName().toString(), content),
                path.toString(),
                content,
                documentHash,
                documentVersion,
                PIPELINE,
                chunks);
    }

    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Calculate runbook hash failed", e);
        }
    }

    private List<RunbookChunk> split(String runbookId, String documentVersion, String content, int maxChunkChars) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<MutableChunk> mutableChunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String sectionTitle = "overview";
        for (String line : content.split("\\R")) {
            boolean newSection = line.startsWith("## ") && current.length() > 0;
            if (newSection) {
                mutableChunks.add(new MutableChunk(sectionTitle, current.toString().trim()));
                current.setLength(0);
                sectionTitle = line.substring(3).trim();
            } else if (line.startsWith("# ")) {
                sectionTitle = line.substring(2).trim();
            } else if (line.startsWith("## ")) {
                sectionTitle = line.substring(3).trim();
            }
            current.append(line).append('\n');
            if (current.length() >= maxChunkChars) {
                mutableChunks.add(new MutableChunk(sectionTitle, current.toString().trim()));
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            mutableChunks.add(new MutableChunk(sectionTitle, current.toString().trim()));
        }
        List<RunbookChunk> chunks = new ArrayList<>();
        int chunkCount = mutableChunks.size();
        for (int i = 0; i < mutableChunks.size(); i++) {
            MutableChunk chunk = mutableChunks.get(i);
            String chunkHash = sha256(chunk.content());
            chunks.add(new RunbookChunk(
                    runbookId + "-v" + documentVersion + "-chunk-" + i,
                    i,
                    chunkCount,
                    chunk.sectionTitle(),
                    chunk.content(),
                    chunkHash));
        }
        return chunks;
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
        if (lowerFileName.contains("connection-pool") || lowerFileName.contains("slow-sql")
                || lowerFileName.contains("deadlock")) {
            return "database";
        }
        if (lowerFileName.contains("redis") || lowerFileName.contains("cache")) {
            return "cache";
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
        if (lowerFileName.contains("kubernetes") || lowerFileName.contains("crashloop")) {
            return "kubernetes";
        }
        if (lowerFileName.contains("payment")) {
            return "payment";
        }
        if (lowerFileName.contains("release") || lowerFileName.contains("gray")) {
            return "release";
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
        if (text.contains("redis") || text.contains("cache")) {
            return "cache";
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
        if (text.contains("payment") || text.contains("callback")) {
            return "payment";
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

    private record MutableChunk(String sectionTitle, String content) {
    }

    public record RunbookDocument(String runbookId,
                                  String title,
                                  String category,
                                  String path,
                                  String content,
                                  String documentHash,
                                  String documentVersion,
                                  String ingestPipeline,
                                  List<RunbookChunk> chunks) {
    }

    public record RunbookChunk(String chunkId,
                               int chunkIndex,
                               int chunkCount,
                               String sectionTitle,
                               String content,
                               String chunkHash) {
    }
}
