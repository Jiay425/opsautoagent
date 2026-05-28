package com.opsautoagent.domain.codeops.agent.tool;

import com.opsautoagent.domain.codeops.model.entity.EngineeringToolDefinitionEntity;
import com.opsautoagent.domain.codeops.model.entity.CodeSnippetEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffContextEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffHunkEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
public class EngineeringToolGateway {

    private static final int MAX_DIFF_LENGTH = 20_000;

    private static final Pattern HUNK_HEADER_PATTERN = Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*");

    private final List<EngineeringToolDefinitionEntity> tools = List.of(
            tool("repo.search_text", "Search code text in the target repository", "READ_ONLY"),
            tool("repo.list_files", "List repository files by pattern", "READ_ONLY"),
            tool("repo.git_diff", "Read current diff or a specified change ref", "READ_ONLY"),
            tool("repo.git_log", "Read recent git history", "READ_ONLY"),
            tool("repo.find_tests", "Find tests related to changed code", "READ_ONLY"),
            tool("knowledge.search", "Search engineering knowledge documents", "READ_ONLY"),
            tool("ops.query_prometheus", "Query metrics for online diagnosis", "READ_ONLY"),
            tool("ops.search_logs", "Search logs for online diagnosis", "READ_ONLY"),
            tool("ops.query_trace", "Query trace evidence for online diagnosis", "READ_ONLY"),
            tool("artifact.generate_review_report", "Generate review report draft", "LOW_RISK_WRITE")
    );

    public List<EngineeringToolDefinitionEntity> listTools() {
        return tools;
    }

    public boolean isToolAllowed(String toolName) {
        return tools.stream()
                .anyMatch(tool -> Boolean.TRUE.equals(tool.getEnabled()) && tool.getToolName().equals(toolName));
    }

    public Map<String, String> createRepositorySnapshot(String repository) {
        Path repositoryPath = resolveRepositoryPath(repository);
        if (!Files.exists(repositoryPath)) {
            return Map.of();
        }
        Map<String, String> snapshot = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(repositoryPath, 14)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isSnapshotFile)
                    .forEach(path -> readSnapshotFile(repositoryPath, path, snapshot));
        } catch (IOException e) {
            log.warn("Create repository snapshot failed. repository={}", repositoryPath, e);
        }
        return snapshot;
    }

    public List<String> searchCode(String repository, List<String> queries, int maxMatches) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        Path repositoryPath = resolveRepositoryPath(repository);
        if (!Files.exists(repositoryPath)) {
            return List.of();
        }
        Map<String, String> normalizedQueries = new LinkedHashMap<>();
        for (String query : queries) {
            if (!isBlank(query) && query.length() >= 2) {
                normalizedQueries.put(query, query.toLowerCase(Locale.ROOT));
            }
        }
        if (normalizedQueries.isEmpty()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(repositoryPath, 14)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isSearchableCodeFile)
                    .forEach(path -> addCodeMatches(repositoryPath, path, normalizedQueries, matches, maxMatches));
        } catch (IOException e) {
            log.warn("Search code failed. repository={}", repositoryPath, e);
        }
        return matches.size() <= maxMatches ? matches : matches.subList(0, maxMatches);
    }

    public CommandResult runMavenCommand(String repository, List<String> args, long timeoutMillis) {
        Path repositoryPath = resolveRepositoryPath(repository);
        List<String> command = new ArrayList<>();
        command.add(isWindows() ? "mvn.cmd" : "mvn");
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repositoryPath.toFile());
        builder.redirectErrorStream(true);
        long start = System.currentTimeMillis();
        try {
            Process process = builder.start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
            boolean completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                destroyProcessTree(process);
                String output = outputFuture.get(2, TimeUnit.SECONDS);
                return new CommandResult(command, false, -1,
                        abbreviate("command timeout after " + timeoutMillis + "ms\n" + output, 6000),
                        System.currentTimeMillis() - start);
            }
            String output = outputFuture.get(2, TimeUnit.SECONDS);
            return new CommandResult(command, process.exitValue() == 0, process.exitValue(), abbreviate(output, 6000), System.currentTimeMillis() - start);
        } catch (IOException e) {
            return new CommandResult(command, false, -1, e.getMessage(), System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(command, false, -1, "interrupted", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new CommandResult(command, false, -1, e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private String readProcessOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "read process output failed: " + e.getMessage();
        }
    }

    private void destroyProcessTree(Process process) {
        process.descendants().forEach(child -> {
            try {
                child.destroyForcibly();
            } catch (Exception ignored) {
                // Best-effort cleanup for Maven child JVMs on timeout.
            }
        });
        process.destroyForcibly();
    }

    public CodeSnippetEntity readFileSnippet(String repository, String filePath, int centerLine, int radius) {
        Path repositoryPath = resolveRepositoryPath(repository);
        if (isBlank(filePath)) {
            return CodeSnippetEntity.builder()
                    .filePath(filePath)
                    .available(false)
                    .errorMessage("filePath is blank")
                    .lines(List.of())
                    .build();
        }
        Path file = repositoryPath.resolve(filePath).normalize();
        if (!file.startsWith(repositoryPath.normalize()) || !Files.exists(file) || !Files.isRegularFile(file)) {
            return CodeSnippetEntity.builder()
                    .filePath(filePath)
                    .available(false)
                    .errorMessage("file does not exist or is outside repository")
                    .lines(List.of())
                    .build();
        }
        try {
            List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int safeCenter = centerLine <= 0 ? 1 : centerLine;
            int start = Math.max(1, safeCenter - Math.max(1, radius));
            int end = Math.min(allLines.size(), safeCenter + Math.max(1, radius));
            List<String> lines = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                lines.add(i + ":" + allLines.get(i - 1));
            }
            return CodeSnippetEntity.builder()
                    .filePath(filePath)
                    .startLine(start)
                    .endLine(end)
                    .lines(lines)
                    .available(true)
                    .build();
        } catch (IOException e) {
            return CodeSnippetEntity.builder()
                    .filePath(filePath)
                    .available(false)
                    .errorMessage(e.getMessage())
                    .lines(List.of())
                    .build();
        }
    }

    private void addCodeMatches(Path repositoryPath, Path path, Map<String, String> queries,
                                List<String> matches, int maxMatches) {
        if (matches.size() >= maxMatches) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && matches.size() < maxMatches; i++) {
                String line = lines.get(i);
                String lowerLine = line.toLowerCase(Locale.ROOT);
                for (Map.Entry<String, String> query : queries.entrySet()) {
                    if (lowerLine.contains(query.getValue())) {
                        matches.add(repositoryPath.relativize(path).toString().replace('\\', '/')
                                + ":" + (i + 1) + " matched [" + query.getKey() + "] " + line.trim());
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
            // Ignore unreadable files during best-effort code search.
        }
    }

    private boolean isSearchableCodeFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".java")
                || fileName.endsWith(".xml")
                || fileName.endsWith(".yml")
                || fileName.endsWith(".yaml")
                || fileName.endsWith(".properties");
    }

    public RepoDiffContextEntity loadDiffContext(String repository, String changeRef) {
        return loadDiffContext(repository, changeRef, Map.of());
    }

    @SuppressWarnings("unchecked")
    public RepoDiffContextEntity loadDiffContext(String repository, String changeRef, Map<String, Object> context) {
        Path repositoryPath = resolveRepositoryPath(repository);
        String normalizedChangeRef = isBlank(changeRef) ? "working_tree" : changeRef.trim();
        try {
            Map<String, String> baselineSnapshot = Map.of();
            if (context != null && context.get("repoBaselineSnapshot") instanceof Map<?, ?> snapshotMap) {
                Map<String, String> values = new LinkedHashMap<>();
                snapshotMap.forEach((key, value) -> values.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
                baselineSnapshot = values;
            }
            String diffText = baselineSnapshot.isEmpty()
                    ? readDiff(repositoryPath, normalizedChangeRef)
                    : buildSnapshotDiff(baselineSnapshot, createRepositorySnapshot(repositoryPath.toString()));
            List<String> changedFiles = baselineSnapshot.isEmpty()
                    ? readChangedFiles(repositoryPath, normalizedChangeRef)
                    : readSnapshotChangedFiles(baselineSnapshot, createRepositorySnapshot(repositoryPath.toString()));
            List<String> relatedTests = findRelatedTests(repositoryPath, changedFiles);
            List<RepoDiffHunkEntity> hunks = parseHunks(diffText);
            return RepoDiffContextEntity.builder()
                    .repositoryPath(repositoryPath.toString())
                    .changeRef(normalizedChangeRef)
                    .changedFiles(changedFiles)
                    .relatedTestFiles(relatedTests)
                    .hunks(hunks)
                    .diffSummary(buildDiffSummary(changedFiles, relatedTests, diffText))
                    .diffText(abbreviate(diffText, MAX_DIFF_LENGTH))
                    .diffAvailable(!isBlank(diffText))
                    .build();
        } catch (Exception e) {
            log.warn("Load repo diff context failed. repository={}, changeRef={}", repositoryPath, normalizedChangeRef, e);
            return RepoDiffContextEntity.builder()
                    .repositoryPath(repositoryPath.toString())
                    .changeRef(normalizedChangeRef)
                    .changedFiles(List.of())
                    .relatedTestFiles(List.of())
                    .hunks(List.of())
                    .diffSummary("读取仓库 diff 失败：" + e.getMessage())
                    .diffText("")
                    .diffAvailable(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private void readSnapshotFile(Path repositoryPath, Path path, Map<String, String> snapshot) {
        try {
            if (Files.size(path) > 256_000L) {
                return;
            }
            String relativePath = repositoryPath.relativize(path).toString().replace('\\', '/');
            snapshot.put(relativePath, Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Skip unreadable files in the best-effort task snapshot.
        }
    }

    private boolean isSnapshotFile(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("/target/")
                || normalized.contains("/.git/")
                || normalized.contains("/node_modules/")
                || normalized.contains("/build/")
                || normalized.contains("/out/")) {
            return false;
        }
        return isSearchableCodeFile(path)
                || path.getFileName().toString().equalsIgnoreCase("pom.xml")
                || path.getFileName().toString().equalsIgnoreCase("README.md");
    }

    private List<String> readSnapshotChangedFiles(Map<String, String> baselineSnapshot, Map<String, String> currentSnapshot) {
        Set<String> files = new LinkedHashSet<>();
        baselineSnapshot.forEach((file, oldContent) -> {
            if (!String.valueOf(oldContent).equals(currentSnapshot.get(file))) {
                files.add(file);
            }
        });
        currentSnapshot.keySet().stream()
                .filter(file -> !baselineSnapshot.containsKey(file))
                .forEach(files::add);
        return new ArrayList<>(files);
    }

    private String buildSnapshotDiff(Map<String, String> baselineSnapshot, Map<String, String> currentSnapshot) {
        StringBuilder diff = new StringBuilder();
        for (String file : readSnapshotChangedFiles(baselineSnapshot, currentSnapshot)) {
            String oldContent = baselineSnapshot.getOrDefault(file, "");
            String newContent = currentSnapshot.getOrDefault(file, "");
            diff.append("diff --git a/").append(file).append(" b/").append(file).append('\n');
            diff.append("--- a/").append(file).append('\n');
            diff.append("+++ b/").append(file).append('\n');
            List<String> oldLines = splitContent(oldContent);
            List<String> newLines = splitContent(newContent);
            diff.append("@@ -1,").append(oldLines.size()).append(" +1,").append(newLines.size()).append(" @@\n");
            oldLines.forEach(line -> diff.append('-').append(line).append('\n'));
            newLines.forEach(line -> diff.append('+').append(line).append('\n'));
        }
        return diff.toString().trim();
    }

    private List<String> splitContent(String content) {
        String normalized = value(content).replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private List<String> readChangedFiles(Path repositoryPath, String changeRef) {
        String output;
        if ("working_tree".equalsIgnoreCase(changeRef)) {
            output = runGit(repositoryPath, List.of("diff", "--name-only"));
            String staged = runGit(repositoryPath, List.of("diff", "--cached", "--name-only"));
            output = output + "\n" + staged;
        } else {
            output = runGit(repositoryPath, List.of("diff", "--name-only", changeRef));
        }
        Set<String> files = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.toLowerCase(Locale.ROOT).startsWith("warning:")) {
                files.add(trimmed);
            }
        }
        return new ArrayList<>(files);
    }

    private String readDiff(Path repositoryPath, String changeRef) {
        if ("working_tree".equalsIgnoreCase(changeRef)) {
            String unstaged = runGit(repositoryPath, List.of("diff", "--", "."));
            String staged = runGit(repositoryPath, List.of("diff", "--cached", "--", "."));
            return String.join("\n", unstaged, staged).trim();
        }
        return runGit(repositoryPath, List.of("diff", changeRef, "--", "."));
    }

    private List<String> findRelatedTests(Path repositoryPath, List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return List.of();
        }
        Set<String> candidateNames = new LinkedHashSet<>();
        for (String file : changedFiles) {
            if (!file.endsWith(".java") || file.contains("/src/test/") || file.contains("\\src\\test\\")) {
                continue;
            }
            String simpleName = stripExtension(Path.of(file).getFileName().toString());
            candidateNames.add(simpleName + "Test.java");
            candidateNames.add(simpleName + "Tests.java");
        }
        if (candidateNames.isEmpty()) {
            return List.of();
        }
        Path testRoot = repositoryPath.resolve("src/test");
        if (!Files.exists(testRoot)) {
            testRoot = repositoryPath;
        }
        Set<String> tests = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(testRoot, 12)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> candidateNames.contains(path.getFileName().toString()))
                    .forEach(path -> tests.add(repositoryPath.relativize(path).toString().replace('\\', '/')));
        } catch (IOException e) {
            log.warn("Find related tests failed. repository={}", repositoryPath, e);
        }
        return new ArrayList<>(tests);
    }

    private List<RepoDiffHunkEntity> parseHunks(String diffText) {
        if (isBlank(diffText)) {
            return List.of();
        }
        List<RepoDiffHunkEntity> hunks = new ArrayList<>();
        String currentFile = null;
        String currentHeader = null;
        int oldLine = 0;
        int newLine = 0;
        int hunkOldStart = 0;
        int hunkNewStart = 0;
        int hunkNewEnd = 0;
        List<String> addedLines = new ArrayList<>();
        StringBuilder snippet = new StringBuilder();

        for (String line : diffText.split("\\R")) {
            if (line.startsWith("diff --git ")) {
                if (currentHeader != null) {
                    hunks.add(buildHunk(currentFile, hunkOldStart, hunkNewStart, hunkNewEnd, currentHeader, addedLines, snippet));
                }
                currentFile = parseDiffFile(line);
                currentHeader = null;
                addedLines = new ArrayList<>();
                snippet = new StringBuilder();
                continue;
            }
            Matcher matcher = HUNK_HEADER_PATTERN.matcher(line);
            if (matcher.matches()) {
                if (currentHeader != null) {
                    hunks.add(buildHunk(currentFile, hunkOldStart, hunkNewStart, hunkNewEnd, currentHeader, addedLines, snippet));
                }
                currentHeader = line;
                oldLine = parseInt(matcher.group(1));
                newLine = parseInt(matcher.group(2));
                hunkOldStart = oldLine;
                hunkNewStart = newLine;
                hunkNewEnd = newLine;
                addedLines = new ArrayList<>();
                snippet = new StringBuilder(line).append('\n');
                continue;
            }
            if (currentHeader == null) {
                continue;
            }
            snippet.append(line).append('\n');
            if (line.startsWith("+") && !line.startsWith("+++")) {
                addedLines.add(newLine + ":" + line.substring(1));
                hunkNewEnd = newLine;
                newLine++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                oldLine++;
            } else {
                oldLine++;
                hunkNewEnd = newLine;
                newLine++;
            }
        }
        if (currentHeader != null) {
            hunks.add(buildHunk(currentFile, hunkOldStart, hunkNewStart, hunkNewEnd, currentHeader, addedLines, snippet));
        }
        return hunks;
    }

    private RepoDiffHunkEntity buildHunk(String filePath, int oldStart, int newStart, int newEnd,
                                         String header, List<String> addedLines, StringBuilder snippet) {
        return RepoDiffHunkEntity.builder()
                .filePath(filePath)
                .oldStartLine(oldStart)
                .newStartLine(newStart)
                .newEndLine(newEnd)
                .header(header)
                .addedLines(new ArrayList<>(addedLines))
                .snippet(abbreviate(snippet.toString(), 2000))
                .build();
    }

    private String parseDiffFile(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length < 4) {
            return "diff";
        }
        String bPath = parts[3];
        return bPath.startsWith("b/") ? bPath.substring(2) : bPath;
    }

    private String runGit(Path repositoryPath, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repositoryPath.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            boolean completed = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return "";
            }
            byte[] bytes = process.getInputStream().readAllBytes();
            String output = new String(bytes, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                log.debug("Git command exited with non-zero status. command={}, output={}", command, output);
            }
            return output;
        } catch (IOException e) {
            log.warn("Run git command failed. command={}, repository={}", command, repositoryPath, e);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private Path resolveRepositoryPath(String repository) {
        Path path = resolveInputPath(repository);
        if (!isBlank(repository) && Files.exists(path) && Files.isDirectory(path)) {
            return path.normalize();
        }
        if (Files.exists(path.resolve(".git"))) {
            return path.normalize();
        }
        Path current = path.normalize();
        for (int i = 0; i < 4 && current.getParent() != null; i++) {
            current = current.getParent();
            if (Files.exists(current.resolve(".git"))) {
                return current.normalize();
            }
        }
        return path.normalize();
    }

    private Path resolveInputPath(String repository) {
        if (isBlank(repository)) {
            return Path.of("").toAbsolutePath().normalize();
        }
        Path raw = Path.of(repository);
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path cwdPath = cwd.resolve(raw).normalize();
        if (Files.exists(cwdPath)) {
            return cwdPath;
        }
        Path parent = cwd.getParent();
        if (parent != null) {
            Path parentPath = parent.resolve(raw).normalize();
            if (Files.exists(parentPath)) {
                return parentPath;
            }
        }
        return cwdPath;
    }

    private String buildDiffSummary(List<String> changedFiles, List<String> relatedTests, String diffText) {
        int addedLines = 0;
        int removedLines = 0;
        for (String line : value(diffText).split("\\R")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                addedLines++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                removedLines++;
            }
        }
        return "changedFiles=" + changedFiles.size()
                + ", relatedTests=" + relatedTests.size()
                + ", addedLines=" + addedLines
                + ", removedLines=" + removedLines;
    }

    private EngineeringToolDefinitionEntity tool(String name, String description, String riskLevel) {
        return EngineeringToolDefinitionEntity.builder()
                .toolName(name)
                .description(description)
                .riskLevel(riskLevel)
                .timeoutMillis(10_000)
                .enabled(true)
                .build();
    }

    private String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "\n...diff truncated...";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    public record CommandResult(List<String> command, boolean success, int exitCode, String output, long costMillis) {
    }

}
