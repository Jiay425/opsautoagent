package com.opsautoagent.domain.codeops.agent.memory;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-layer incident memory system.
 *
 * Layer 1 — Short-term: current task reflection context, held in-memory during task execution
 * Layer 2 — Case memory: historical successful fix patterns (file-based persistence)
 * Layer 3 — Failure memory: failed attempts with what went wrong (file-based, injected as AVOID hints)
 * Layer 4 — Team knowledge: Runbook, architecture specs, deployment standards (placeholder for RAG)
 *
 * Storage structure:
 *   data/incident-memory/
 *     MEMORY.md                          — master index (all patterns)
 *     success/{hash}.json                — successful fix patterns
 *     failures/{hash}.json               — failure records (guard violations, compile errors, ...)
 *     team-knowledge/                    — imported runbook/architecture docs
 */
@Slf4j
@Service
public class IncidentMemoryService {

    private static final Path MEMORY_DIR = Path.of("data/incident-memory");
    private static final Path SUCCESS_DIR = MEMORY_DIR.resolve("success");
    private static final Path FAILURES_DIR = MEMORY_DIR.resolve("failures");
    private static final Path TEAM_DIR = MEMORY_DIR.resolve("team-knowledge");
    private static final Path INDEX_FILE = MEMORY_DIR.resolve("MEMORY.md");

    // --- Layer 2: Case Memory (success) ---

    public void storeSuccess(String caseId, String caseName, String scopeType,
                              List<String> targetMethods, String rootCause,
                              String fixStrategy, String riskLevel) {
        ensureDirs();
        String hash = hashOf(caseId + caseName);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "success");
        record.put("caseId", caseId);
        record.put("caseName", caseName);
        record.put("scopeType", scopeType);
        record.put("targetMethods", targetMethods);
        record.put("rootCause", truncate(rootCause, 800));
        record.put("fixStrategy", truncate(fixStrategy, 500));
        record.put("riskLevel", riskLevel);
        record.put("storedAt", now());

        writeJson(SUCCESS_DIR.resolve(hash + ".json"), record);
        appendIndex("success", hash, caseName, scopeType, rootCause);
        log.info("Success pattern stored: {} -> {}", caseId, hash);
    }

    // --- Layer 3: Failure Memory ---

    public void storeFailure(String caseId, String caseName, String failureType,
                              String failedMethod, String violation, String scopeType) {
        ensureDirs();
        String hash = hashOf(caseId + failureType + System.currentTimeMillis());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "failure");
        record.put("caseId", caseId);
        record.put("caseName", caseName);
        record.put("failureType", failureType);
        record.put("failedMethod", failedMethod);
        record.put("violation", violation);
        record.put("scopeType", scopeType);
        record.put("storedAt", now());

        writeJson(FAILURES_DIR.resolve(hash + ".json"), record);
        appendIndex("failure", hash, caseName, failureType, truncate(violation, 100));
        log.info("Failure pattern stored: {} ({}): {}", caseId, failureType, truncate(violation, 120));
    }

    // --- Unified Recall (Layers 2 + 3 + 4) ---

    public MemoryRecallResult recall(List<String> keywords) {
        MemoryRecallResult result = new MemoryRecallResult();
        ensureDirs();

        // Layer 2: success patterns
        List<Map<String, Object>> successes = searchDir(SUCCESS_DIR, keywords, 0.5);
        for (Map<String, Object> s : successes) {
            s.put("_hintType", "SUGGEST");
            s.put("_hintLabel", "Previously succeeded");
        }
        result.successPatterns = successes;

        // Layer 3: failure patterns
        List<Map<String, Object>> failures = searchDir(FAILURES_DIR, keywords, 0.3);
        for (Map<String, Object> f : failures) {
            f.put("_hintType", "AVOID");
            f.put("_hintLabel", "Previously failed — DO NOT repeat");
        }
        result.failurePatterns = failures;

        // Layer 4: team knowledge (runbook/architecture)
        List<Map<String, Object>> teamDocs = searchDir(TEAM_DIR, keywords, 0.2);
        for (Map<String, Object> t : teamDocs) {
            t.put("_hintType", "REFERENCE");
            t.put("_hintLabel", "Team knowledge");
        }
        result.teamReferences = teamDocs;

        return result;
    }

    /**
     * Build a memory prompt section for the LLM.
     */
    public String buildMemoryPrompt(List<String> keywords) {
        MemoryRecallResult recall = recall(keywords);
        StringBuilder sb = new StringBuilder();

        if (!recall.failurePatterns.isEmpty()) {
            sb.append("\n### AVOID — Previously Failed Attempts (DO NOT REPEAT)\n");
            for (Map<String, Object> f : recall.failurePatterns) {
                sb.append("- FAILED: ").append(f.getOrDefault("caseName", ""))
                        .append(" | ").append(f.getOrDefault("failureType", ""))
                        .append(" | ").append(truncate(String.valueOf(f.getOrDefault("violation", "")), 150))
                        .append("\n");
            }
        }

        if (!recall.successPatterns.isEmpty()) {
            sb.append("\n### REFERENCE — Previously Successful Fixes\n");
            for (Map<String, Object> s : recall.successPatterns) {
                sb.append("- OK: ").append(s.getOrDefault("caseName", ""))
                        .append(" | ").append(s.getOrDefault("scopeType", ""))
                        .append(" | ").append(truncate(String.valueOf(s.getOrDefault("rootCause", "")), 120))
                        .append("\n");
            }
        }

        if (!recall.teamReferences.isEmpty()) {
            sb.append("\n### TEAM KNOWLEDGE\n");
            for (Map<String, Object> t : recall.teamReferences) {
                sb.append("- ").append(t.getOrDefault("title", ""))
                        .append(": ").append(truncate(String.valueOf(t.getOrDefault("summary", "")), 150))
                        .append("\n");
            }
        }

        return sb.toString();
    }

    // --- Short-term memory (Layer 1) — in-memory context ---

    public Map<String, Object> buildShortTermContext(List<Map<String, Object>> reflectionDiagnostics,
                                                      int currentRound, String lastError) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("reflectionRound", currentRound);
        context.put("diagnosticsCount", reflectionDiagnostics != null ? reflectionDiagnostics.size() : 0);
        context.put("lastError", truncate(lastError, 500));

        // Summarize what's been tried
        List<String> attemptedStrategies = new ArrayList<>();
        if (reflectionDiagnostics != null) {
            for (Map<String, Object> d : reflectionDiagnostics) {
                Object ft = d.get("failureType");
                if (ft != null) attemptedStrategies.add("Round " + d.get("round") + ": " + ft);
            }
        }
        context.put("attemptedStrategies", attemptedStrategies);
        context.put("shouldTrySomethingDifferent", attemptedStrategies.size() >= 2);

        return context;
    }

    // --- Index management ---

    public Map<String, Object> loadMemoryPrompt() {
        Map<String, Object> prompt = new LinkedHashMap<>();
        try {
            if (Files.exists(INDEX_FILE)) {
                List<String> lines = Files.readAllLines(INDEX_FILE, StandardCharsets.UTF_8);
                List<String> recent = lines.subList(Math.max(0, lines.size() - 20), lines.size());
                prompt.put("recentEntries", recent);
                prompt.put("totalEntries", lines.size());
            }
        } catch (IOException e) {
            prompt.put("error", e.getMessage());
        }
        return prompt;
    }

    // --- Internal ---

    private void appendIndex(String type, String hash, String name, String category, String summary) {
        try {
            Files.createDirectories(MEMORY_DIR);
            List<String> lines = new ArrayList<>();
            if (Files.exists(INDEX_FILE)) {
                lines.addAll(Files.readAllLines(INDEX_FILE, StandardCharsets.UTF_8));
            }
            while (lines.size() >= 200) lines.remove(0);

            String icon = "success".equals(type) ? "OK" : "FAIL";
            String dir = "success".equals(type) ? "success" : "failures";
            String entry = String.format("- [%s] [%s](%s/%s.json) — %s | %s | %s",
                    icon, name, dir, hash, category, truncate(summary, 80), now());
            lines.add(entry);
            Files.write(INDEX_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to update memory index: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> searchDir(Path dir, List<String> keywords, double minRatio) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (!Files.exists(dir)) return results;

        try {
            List<Path> files;
            try (var paths = Files.list(dir)) {
                files = paths.filter(p -> p.toString().endsWith(".json"))
                        .sorted((a, b) -> -Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                        .limit(20)
                        .toList();
            }

            for (Path file : files) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String lower = content.toLowerCase();
                long matches = keywords.stream().filter(k -> lower.contains(k.toLowerCase())).count();
                double ratio = keywords.isEmpty() ? 0 : (double) matches / keywords.size();
                if (ratio >= minRatio && matches > 0) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> record = JSON.parseObject(content, Map.class);
                    record.put("_matchScore", matches);
                    record.put("_matchRatio", String.format("%.0f%%", ratio * 100));
                    results.add(record);
                }
            }
        } catch (IOException e) {
            log.warn("Memory search failed: {}", e.getMessage());
        }

        results.sort((a, b) -> Long.compare(
                (Long) b.getOrDefault("_matchScore", 0L),
                (Long) a.getOrDefault("_matchScore", 0L)));
        return results;
    }

    private void ensureDirs() {
        try {
            Files.createDirectories(SUCCESS_DIR);
            Files.createDirectories(FAILURES_DIR);
            Files.createDirectories(TEAM_DIR);
        } catch (IOException ignored) {}
    }

    private void writeJson(Path file, Map<String, Object> data) {
        try {
            Files.writeString(file, JSON.toJSONString(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to write memory file: {}", e.getMessage());
        }
    }

    private String hashOf(String input) {
        return Integer.toHexString(input.hashCode());
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen) + "...";
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    // --- Recall result DTO ---

    public static class MemoryRecallResult {
        public List<Map<String, Object>> successPatterns = List.of();
        public List<Map<String, Object>> failurePatterns = List.of();
        public List<Map<String, Object>> teamReferences = List.of();

        public boolean hasFailures() { return !failurePatterns.isEmpty(); }
        public boolean hasSuccesses() { return !successPatterns.isEmpty(); }
        public boolean hasTeamKnowledge() { return !teamReferences.isEmpty(); }
        public boolean isEmpty() { return !hasFailures() && !hasSuccesses() && !hasTeamKnowledge(); }
    }
}
