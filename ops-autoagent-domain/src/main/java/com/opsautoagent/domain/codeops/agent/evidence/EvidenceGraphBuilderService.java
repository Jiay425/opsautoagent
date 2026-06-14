package com.opsautoagent.domain.codeops.agent.evidence;

import com.opsautoagent.domain.codeops.model.entity.CodeSnippetEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EvidenceGraphEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EvidenceGraphBuilderService {

    private static final int MAX_NODES = 40;
    private static final int MAX_EDGES = 80;

    public EvidenceGraphEntity build(EngineeringTaskEntity task,
                                     Map<String, Object> opsDiagnosis,
                                     List<String> codeHints,
                                     List<String> codeSearchMatches,
                                     List<CodeSnippetEntity> codeSnippets) {
        Map<String, EvidenceGraphEntity.EvidenceNodeEntity> nodes = new LinkedHashMap<>();
        List<EvidenceGraphEntity.EvidenceEdgeEntity> edges = new ArrayList<>();
        List<String> seeds = collectSeeds(task, opsDiagnosis, codeHints);

        addSignalNodes(nodes, seeds, task, opsDiagnosis);
        addSearchMatchNodes(nodes, edges, codeSearchMatches, seeds);
        addSnippetNodes(nodes, edges, codeSnippets, seeds);
        addCallRelationEdges(nodes, edges, codeSnippets);

        List<String> rankedCodeNodes = nodes.values().stream()
                .filter(node -> "CODE".equals(node.getType()))
                .sorted((a, b) -> Integer.compare(score(b), score(a)))
                .map(node -> node.getLabel() + " score=" + score(node))
                .limit(12)
                .toList();

        return EvidenceGraphEntity.builder()
                .nodes(nodes.values().stream().limit(MAX_NODES).toList())
                .edges(edges.stream().limit(MAX_EDGES).toList())
                .localizationSeeds(seeds.stream().limit(30).toList())
                .rankedCodeNodes(rankedCodeNodes)
                .summary("Evidence graph: " + nodes.size() + " nodes, " + edges.size()
                        + " edges. Top code nodes: " + String.join("; ", rankedCodeNodes))
                .build();
    }

    private void addSignalNodes(Map<String, EvidenceGraphEntity.EvidenceNodeEntity> nodes,
                                List<String> seeds,
                                EngineeringTaskEntity task,
                                Map<String, Object> opsDiagnosis) {
        if (task != null && !blank(task.getGoal())) {
            putNode(nodes, "alert:goal", "ALERT", "incident goal", "TASK", task.getGoal(), 90);
        }
        Object endpoint = task == null || task.getContext() == null ? null : task.getContext().get("endpoint");
        if (endpoint != null && !blank(String.valueOf(endpoint))) {
            putNode(nodes, "alert:endpoint", "ALERT", String.valueOf(endpoint), "TASK_CONTEXT",
                    "Affected endpoint from task context", 95);
        }
        for (String seed : seeds) {
            String type = classifySeed(seed);
            putNode(nodes, "signal:" + normalizeId(seed), type, seed, "OPS_SIGNAL",
                    "Localization seed from alert, diagnosis or task context", seedScore(seed));
        }
        if (opsDiagnosis != null && !opsDiagnosis.isEmpty()) {
            putNode(nodes, "ops:diagnosis", "DIAGNOSIS", "ops diagnosis", "OPS_DIAGNOSIS",
                    abbreviate(String.valueOf(opsDiagnosis), 500), 80);
        }
    }

    private void addSearchMatchNodes(Map<String, EvidenceGraphEntity.EvidenceNodeEntity> nodes,
                                     List<EvidenceGraphEntity.EvidenceEdgeEntity> edges,
                                     List<String> codeSearchMatches,
                                     List<String> seeds) {
        if (codeSearchMatches == null) {
            return;
        }
        int index = 0;
        for (String match : codeSearchMatches) {
            if (blank(match) || index >= 30) {
                continue;
            }
            index++;
            MatchLocation location = parseMatchLocation(match);
            String label = location == null ? match : location.filePath() + ":" + location.lineNumber();
            int score = scoreText(match, seeds) + 40;
            String nodeId = "code-search:" + normalizeId(label);
            putNode(nodes, nodeId, "CODE", label, "REPO_SEARCH", abbreviate(match, 240), score);
            for (String seed : matchedSeeds(match, seeds)) {
                edges.add(edge("signal:" + normalizeId(seed), nodeId, "MATCHES_CODE",
                        "Search result contains seed `" + seed + "`", 60));
            }
        }
    }

    private void addSnippetNodes(Map<String, EvidenceGraphEntity.EvidenceNodeEntity> nodes,
                                 List<EvidenceGraphEntity.EvidenceEdgeEntity> edges,
                                 List<CodeSnippetEntity> snippets,
                                 List<String> seeds) {
        if (snippets == null) {
            return;
        }
        for (CodeSnippetEntity snippet : snippets) {
            if (snippet == null || !Boolean.TRUE.equals(snippet.getAvailable()) || blank(snippet.getFilePath())) {
                continue;
            }
            String text = String.join("\n", snippet.getLines() == null ? List.of() : snippet.getLines());
            String nodeId = "code-file:" + normalizeId(snippet.getFilePath());
            int score = scoreText(snippet.getFilePath() + "\n" + text, seeds) + 50;
            putNode(nodes, nodeId, "CODE", snippet.getFilePath(), "CODE_SNIPPET",
                    "lines " + snippet.getStartLine() + "-" + snippet.getEndLine(), score);

            for (String seed : matchedSeeds(snippet.getFilePath() + "\n" + text, seeds)) {
                edges.add(edge("signal:" + normalizeId(seed), nodeId, "EVIDENCE_SUPPORTS_CODE",
                        "Code snippet contains seed `" + seed + "`", 70));
            }

            for (String method : extractMethodNames(text)) {
                String methodId = "code-method:" + simpleClassName(snippet.getFilePath()) + "." + method;
                putNode(nodes, methodId, "CODE", simpleClassName(snippet.getFilePath()) + "." + method,
                        "CODE_SNIPPET", snippet.getFilePath(), score + 10);
                edges.add(edge(nodeId, methodId, "DECLARES_METHOD", "Method declared in snippet", 50));
            }
        }
    }

    private void addCallRelationEdges(Map<String, EvidenceGraphEntity.EvidenceNodeEntity> nodes,
                                      List<EvidenceGraphEntity.EvidenceEdgeEntity> edges,
                                      List<CodeSnippetEntity> snippets) {
        if (snippets == null) {
            return;
        }
        for (CodeSnippetEntity snippet : snippets) {
            if (snippet == null || !Boolean.TRUE.equals(snippet.getAvailable()) || blank(snippet.getFilePath())) {
                continue;
            }
            String className = simpleClassName(snippet.getFilePath());
            String text = String.join("\n", snippet.getLines() == null ? List.of() : snippet.getLines());
            Set<String> callees = extractServiceCalls(text);
            for (String callee : callees) {
                String targetFileId = "code-file:" + normalizeId(resolveSamePackageFile(snippet.getFilePath(), callee));
                String sourceId = "code-file:" + normalizeId(snippet.getFilePath());
                if (!nodes.containsKey(targetFileId)) {
                    putNode(nodes, targetFileId, "CODE", resolveSamePackageFile(snippet.getFilePath(), callee),
                            "CALL_RELATION", className + " references " + callee, 55);
                }
                edges.add(edge(sourceId, targetFileId, "CALLS_OR_REFERENCES",
                        className + " references " + callee + " in visible code", 65));
            }
        }
    }

    private List<String> collectSeeds(EngineeringTaskEntity task, Map<String, Object> opsDiagnosis, List<String> codeHints) {
        Set<String> seeds = new LinkedHashSet<>();
        if (codeHints != null) {
            codeHints.stream().filter(value -> !blank(value)).forEach(seeds::add);
        }
        if (task != null) {
            addTokens(seeds, task.getGoal());
            if (task.getContext() != null) {
                addTokens(seeds, String.valueOf(task.getContext().getOrDefault("endpoint", "")));
                addTokens(seeds, String.valueOf(task.getContext().getOrDefault("serviceName", "")));
            }
        }
        if (opsDiagnosis != null) {
            addTokens(seeds, String.valueOf(opsDiagnosis));
        }
        return seeds.stream()
                .filter(seed -> seed.length() >= 3)
                .distinct()
                .limit(40)
                .toList();
    }

    private void addTokens(Set<String> seeds, String text) {
        if (blank(text)) {
            return;
        }
        Matcher matcher = Pattern.compile("([A-Z][A-Za-z0-9_]*(?:Service|Controller|Repository|Exception|Error)|/[a-zA-Z0-9_./-]+|[a-zA-Z][a-zA-Z0-9_]{3,})")
                .matcher(text);
        while (matcher.find()) {
            seeds.add(matcher.group(1));
        }
    }

    private void putNode(Map<String, EvidenceGraphEntity.EvidenceNodeEntity> nodes,
                         String id,
                         String type,
                         String label,
                         String source,
                         String detail,
                         int score) {
        if (blank(id) || nodes.size() >= MAX_NODES && !nodes.containsKey(id)) {
            return;
        }
        EvidenceGraphEntity.EvidenceNodeEntity existing = nodes.get(id);
        if (existing != null) {
            existing.setScore(Math.max(score(existing), score));
            return;
        }
        nodes.put(id, EvidenceGraphEntity.EvidenceNodeEntity.builder()
                .id(id)
                .type(type)
                .label(label)
                .source(source)
                .detail(detail)
                .score(score)
                .build());
    }

    private EvidenceGraphEntity.EvidenceEdgeEntity edge(String from, String to, String relation, String reason, int weight) {
        return EvidenceGraphEntity.EvidenceEdgeEntity.builder()
                .from(from)
                .to(to)
                .relation(relation)
                .reason(reason)
                .weight(weight)
                .build();
    }

    private List<String> matchedSeeds(String text, List<String> seeds) {
        if (blank(text) || seeds == null || seeds.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return seeds.stream()
                .filter(seed -> !blank(seed) && lower.contains(seed.toLowerCase(Locale.ROOT)))
                .distinct()
                .limit(8)
                .toList();
    }

    private int scoreText(String text, List<String> seeds) {
        int score = 0;
        for (String ignored : matchedSeeds(text, seeds)) {
            score += 10;
        }
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("exception") || lower.contains("duplicate") || lower.contains("requestid")) {
            score += 15;
        }
        if (lower.contains("synchronized") || lower.contains("concurrent") || lower.contains("atomic")) {
            score += 10;
        }
        return score;
    }

    private List<String> extractMethodNames(String text) {
        if (blank(text)) {
            return List.of();
        }
        Set<String> methods = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\\b(?:public|private|protected)\\s+(?:synchronized\\s+)?[A-Za-z0-9_<>, ?]+\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(")
                .matcher(text);
        while (matcher.find()) {
            methods.add(matcher.group(1));
        }
        return methods.stream().limit(12).toList();
    }

    private Set<String> extractServiceCalls(String text) {
        Set<String> calls = new LinkedHashSet<>();
        if (blank(text)) {
            return calls;
        }
        Matcher matcher = Pattern.compile("\\b([a-z][A-Za-z0-9_]*(?:Service|Repository|Client|Mapper))\\.([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(")
                .matcher(text);
        while (matcher.find()) {
            calls.add(toClassName(matcher.group(1)));
        }
        return calls;
    }

    private MatchLocation parseMatchLocation(String match) {
        int firstColon = match == null ? -1 : match.indexOf(':');
        int secondColon = firstColon < 0 ? -1 : match.indexOf(':', firstColon + 1);
        if (firstColon <= 0 || secondColon <= firstColon) {
            return null;
        }
        try {
            return new MatchLocation(match.substring(0, firstColon), Integer.parseInt(match.substring(firstColon + 1, secondColon).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String classifySeed(String seed) {
        String lower = seed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/")) return "ENDPOINT";
        if (lower.contains("trace") || lower.contains("span")) return "TRACE";
        if (lower.contains("5xx") || lower.contains("latency") || lower.contains("metric")) return "METRIC";
        if (lower.contains("exception") || lower.contains("duplicate") || lower.contains("requestid")) return "LOG";
        return "SIGNAL";
    }

    private int seedScore(String seed) {
        String lower = seed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/") || lower.contains("submit") || lower.contains("requestid")) return 90;
        if (lower.contains("service") || lower.contains("repository") || lower.contains("controller")) return 80;
        return 60;
    }

    private String simpleClassName(String filePath) {
        if (blank(filePath)) {
            return "";
        }
        String normalized = filePath.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - ".java".length()) : fileName;
    }

    private String resolveSamePackageFile(String sourceFile, String className) {
        if (blank(sourceFile) || blank(className)) {
            return className + ".java";
        }
        String normalized = sourceFile.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? className + ".java" : normalized.substring(0, slash + 1) + className + ".java";
    }

    private String toClassName(String fieldName) {
        if (blank(fieldName)) {
            return "";
        }
        return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private String normalizeId(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\\', '/')
                .replaceAll("[^a-zA-Z0-9_./:-]", "_")
                .replaceAll("_+", "_");
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength)) + "...";
    }

    private int score(EvidenceGraphEntity.EvidenceNodeEntity node) {
        return node == null || node.getScore() == null ? 0 : node.getScore();
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record MatchLocation(String filePath, int lineNumber) {
    }
}
