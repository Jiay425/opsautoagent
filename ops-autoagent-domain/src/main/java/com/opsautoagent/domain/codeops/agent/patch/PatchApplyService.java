package com.opsautoagent.domain.codeops.agent.patch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.opsautoagent.domain.codeops.agent.security.AgentPermissionPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PatchApplyService {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final PatchValidationService patchValidationService;
    private final AgentPermissionPolicy permissionPolicy;

    public PatchApplyService(PatchValidationService patchValidationService,
                             AgentPermissionPolicy permissionPolicy) {
        this.patchValidationService = patchValidationService;
        this.permissionPolicy = permissionPolicy;
    }

    public PatchApplyResult apply(String repositoryPath, String unifiedDiffPatch) {
        String normalizedPatch = normalizeUnifiedDiff(unifiedDiffPatch);
        PatchValidationResult validation = patchValidationService.validate(repositoryPath, normalizedPatch);
        if (!validation.isValid()) {
            return PatchApplyResult.builder()
                    .requested(true)
                    .applied(false)
                    .checkPassed(false)
                    .repositoryPath(value(repositoryPath))
                    .command(List.of())
                    .exitCode(-1)
                    .output("")
                    .errorMessage("patch validation failed: " + String.join("; ", validation.getErrors()))
                    .build();
        }
        List<String> permissionErrors = validation.getTouchedFiles().stream()
                .filter(file -> !permissionPolicy.isWriteAllowed(repositoryPath, file))
                .map(file -> "write path not allowed: " + file)
                .toList();
        if (!permissionErrors.isEmpty()) {
            return PatchApplyResult.builder()
                    .requested(true)
                    .applied(false)
                    .checkPassed(false)
                    .repositoryPath(value(repositoryPath))
                    .command(List.of())
                    .exitCode(-1)
                    .output("")
                    .errorMessage("PERMISSION_DENIED: " + String.join("; ", permissionErrors))
                    .build();
        }
        Path repoRoot = Path.of(repositoryPath).toAbsolutePath().normalize();
        if (!Files.exists(repoRoot)) {
            return PatchApplyResult.builder()
                    .requested(true)
                    .applied(false)
                    .checkPassed(false)
                    .repositoryPath(repoRoot.toString())
                    .command(List.of())
                    .exitCode(-1)
                    .output("")
                    .errorMessage("repository path does not exist")
                    .build();
        }
        Path patchFile = null;
        try {
            patchFile = Files.createTempFile("codeops-patch-", ".diff");
            Files.writeString(patchFile, normalizedPatch, StandardCharsets.UTF_8);
            CommandResult check = runGitApply(repoRoot, patchFile, true);
            if (!check.success()) {
                CommandResult fallback = applyByContentMatch(repoRoot, normalizedPatch, check.output());
                if (fallback.success()) {
                    return PatchApplyResult.builder()
                            .requested(true)
                            .applied(true)
                            .checkPassed(false)
                            .repositoryPath(repoRoot.toString())
                            .command(fallback.command())
                            .exitCode(0)
                            .output(fallback.output())
                            .errorMessage("")
                            .build();
                }
                return PatchApplyResult.builder()
                        .requested(true)
                        .applied(false)
                        .checkPassed(false)
                        .repositoryPath(repoRoot.toString())
                        .command(check.command())
                        .exitCode(check.exitCode())
                        .output(fallback.output())
                        .errorMessage("git apply --check failed")
                        .build();
            }
            CommandResult apply = runGitApply(repoRoot, patchFile, false);
            if (!apply.success()) {
                CommandResult fallback = applyByContentMatch(repoRoot, normalizedPatch, apply.output());
                if (fallback.success()) {
                    return PatchApplyResult.builder()
                            .requested(true)
                            .applied(true)
                            .checkPassed(true)
                            .repositoryPath(repoRoot.toString())
                            .command(fallback.command())
                            .exitCode(0)
                            .output(fallback.output())
                            .errorMessage("")
                            .build();
                }
            }
            return PatchApplyResult.builder()
                    .requested(true)
                    .applied(apply.success())
                    .checkPassed(true)
                    .repositoryPath(repoRoot.toString())
                    .command(apply.command())
                    .exitCode(apply.exitCode())
                    .output(apply.output())
                    .errorMessage(apply.success() ? "" : "git apply failed")
                    .build();
        } catch (IOException e) {
            log.warn("Apply patch failed. repository={}", repositoryPath, e);
            return PatchApplyResult.builder()
                    .requested(true)
                    .applied(false)
                    .checkPassed(false)
                    .repositoryPath(value(repositoryPath))
                    .command(List.of())
                    .exitCode(-1)
                    .output("")
                    .errorMessage(e.getMessage())
                    .build();
        } finally {
            if (patchFile != null) {
                try {
                    Files.deleteIfExists(patchFile);
                } catch (IOException ignored) {
                    // Temporary patch cleanup is best-effort.
                }
            }
        }
    }

    private CommandResult applyByContentMatch(Path repoRoot, String normalizedPatch, String previousOutput) {
        List<String> command = List.of("content-match-apply");
        Map<Path, String> originalContents = new LinkedHashMap<>();
        List<Path> createdFiles = new ArrayList<>();
        try {
            Map<String, List<Hunk>> hunksByFile = parseHunks(normalizedPatch);
            if (hunksByFile.isEmpty()) {
                return new CommandResult(command, false, -1, previousOutput + "\ncontent-match fallback: no hunks");
            }
            List<String> appliedFiles = new ArrayList<>();
            for (Map.Entry<String, List<Hunk>> entry : hunksByFile.entrySet()) {
                Path file = repoRoot.resolve(entry.getKey()).normalize();
                if (!file.startsWith(repoRoot)) {
                    return rollbackAndFail(originalContents, createdFiles, command,
                            previousOutput + "\ncontent-match fallback: invalid file " + entry.getKey());
                }
                if (!Files.exists(file)) {
                    if (!isNewFilePatch(normalizedPatch, entry.getKey(), entry.getValue())) {
                        return rollbackAndFail(originalContents, createdFiles, command,
                                previousOutput + "\ncontent-match fallback: invalid file " + entry.getKey());
                    }
                    Path parent = file.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    List<String> newFileLines = new ArrayList<>();
                    for (Hunk hunk : entry.getValue()) {
                        newFileLines.addAll(hunk.newLines());
                    }
                    Files.writeString(file, String.join("\n", newFileLines) + "\n", StandardCharsets.UTF_8);
                    createdFiles.add(file);
                    appliedFiles.add(entry.getKey());
                    continue;
                }
                if (!Files.isRegularFile(file)) {
                    return rollbackAndFail(originalContents, createdFiles, command,
                            previousOutput + "\ncontent-match fallback: invalid file " + entry.getKey());
                }
                originalContents.putIfAbsent(file, Files.readString(file, StandardCharsets.UTF_8));
                List<String> fileLines = splitLines(Files.readString(file, StandardCharsets.UTF_8));
                for (Hunk hunk : entry.getValue()) {
                    int index = findSubsequence(fileLines, hunk.oldLines());
                    if (index < 0) {
                        index = hunk.oldStartLine() <= 0 ? -1 : hunk.oldStartLine() - 1;
                    }
                    if (index < 0 || index + hunk.oldLines().size() > fileLines.size()) {
                        if (isWholeFileRewrite(hunk, fileLines)) {
                            fileLines = new ArrayList<>(hunk.newLines());
                            continue;
                        }
                        return rollbackAndFail(originalContents, createdFiles, command,
                                previousOutput + "\ncontent-match fallback: context not found in " + entry.getKey());
                    }
                    fileLines.subList(index, index + hunk.oldLines().size()).clear();
                    fileLines.addAll(index, hunk.newLines());
                }
                Files.writeString(file, String.join("\n", fileLines) + "\n", StandardCharsets.UTF_8);
                appliedFiles.add(entry.getKey());
            }
            return new CommandResult(command, true, 0,
                    previousOutput + "\ncontent-match fallback applied: " + String.join(", ", appliedFiles));
        } catch (IOException e) {
            return rollbackAndFail(originalContents, createdFiles, command,
                    previousOutput + "\ncontent-match fallback: " + e.getMessage());
        }
    }

    private boolean isNewFilePatch(String normalizedPatch, String filePath, List<Hunk> hunks) {
        if (hunks != null && !hunks.isEmpty() && hunks.stream().allMatch(Hunk::newFile)) {
            return true;
        }
        String normalizedFile = extractDiffPath(filePath);
        boolean previousWasDevNull = false;
        for (String rawLine : value(normalizedPatch).split("\n", -1)) {
            String line = rawLine.trim();
            if (line.startsWith("--- ")) {
                previousWasDevNull = isDevNullPath(line.substring(4).trim());
                continue;
            }
            if (line.startsWith("+++ ")) {
                String targetFile = extractDiffPath(line.substring(4).trim());
                if (previousWasDevNull && normalizedFile.equals(targetFile)) {
                    return true;
                }
                previousWasDevNull = false;
            }
        }
        return false;
    }

    private Map<String, List<Hunk>> parseHunks(String patch) {
        Map<String, List<Hunk>> result = new LinkedHashMap<>();
        String currentFile = "";
        List<String> oldLines = new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        int oldStartLine = -1;
        boolean inHunk = false;
        boolean newFile = false;
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("--- ")) {
                flushHunk(result, currentFile, oldStartLine, oldLines, newLines, newFile);
                oldLines = new ArrayList<>();
                newLines = new ArrayList<>();
                oldStartLine = -1;
                inHunk = false;
                newFile = isDevNullPath(line.substring(4).trim());
                continue;
            }
            if (line.startsWith("+++ ")) {
                currentFile = extractDiffPath(line.substring(4).trim());
                result.putIfAbsent(currentFile, new ArrayList<>());
                inHunk = false;
                continue;
            }
            if (line.startsWith("@@")) {
                flushHunk(result, currentFile, oldStartLine, oldLines, newLines, newFile);
                oldLines = new ArrayList<>();
                newLines = new ArrayList<>();
                oldStartLine = parseOldStartLine(line);
                inHunk = true;
                continue;
            }
            if (!inHunk || currentFile.isBlank()) {
                continue;
            }
            if (line.startsWith(" ")) {
                if (!newFile) {
                    oldLines.add(line.substring(1));
                }
                newLines.add(line.substring(1));
            } else if (line.startsWith("-")) {
                oldLines.add(line.substring(1));
            } else if (line.startsWith("+")) {
                newLines.add(line.substring(1));
            }
        }
        flushHunk(result, currentFile, oldStartLine, oldLines, newLines, newFile);
        result.entrySet().removeIf(entry -> entry.getKey().isBlank() || entry.getValue().isEmpty());
        return result;
    }

    private void flushHunk(Map<String, List<Hunk>> result,
                           String currentFile,
                           int oldStartLine,
                           List<String> oldLines,
                           List<String> newLines,
                           boolean newFile) {
        if (currentFile == null || currentFile.isBlank() || newLines.isEmpty()) {
            return;
        }
        if (!newFile && oldLines.isEmpty() && oldStartLine != 0) {
            return;
        }
        result.putIfAbsent(currentFile, new ArrayList<>());
        result.get(currentFile).add(new Hunk(oldStartLine, List.copyOf(oldLines), List.copyOf(newLines), newFile));
    }

    private int parseOldStartLine(String hunkHeader) {
        int minus = hunkHeader.indexOf('-');
        if (minus < 0) {
            return -1;
        }
        int comma = hunkHeader.indexOf(',', minus);
        int space = hunkHeader.indexOf(' ', minus);
        int end = comma > 0 ? comma : space;
        if (end <= minus + 1) {
            return -1;
        }
        try {
            return Integer.parseInt(hunkHeader.substring(minus + 1, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String extractDiffPath(String rawPath) {
        String path = value(rawPath).split("\\s+", 2)[0].replace('\\', '/');
        if (path.startsWith("a/") || path.startsWith("b/")) {
            path = path.substring(2);
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path;
    }

    private boolean isDevNullPath(String rawPath) {
        String path = value(rawPath).split("\\s+", 2)[0].replace('\\', '/');
        return "/dev/null".equals(path) || "dev/null".equals(path);
    }

    private CommandResult rollbackAndFail(Map<Path, String> originalContents,
                                          List<Path> createdFiles,
                                          List<String> command,
                                          String output) {
        for (Map.Entry<Path, String> entry : originalContents.entrySet()) {
            try {
                Files.writeString(entry.getKey(), entry.getValue(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Fallback rollback is best-effort.
            }
        }
        for (Path createdFile : createdFiles) {
            try {
                Files.deleteIfExists(createdFile);
            } catch (IOException ignored) {
                // Fallback rollback is best-effort.
            }
        }
        return new CommandResult(command, false, -1, output);
    }

    private List<String> splitLines(String content) {
        String normalized = value(content).replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private int findSubsequence(List<String> lines, List<String> target) {
        if (target.isEmpty() || target.size() > lines.size()) {
            return -1;
        }
        for (int i = 0; i <= lines.size() - target.size(); i++) {
            boolean matched = true;
            for (int j = 0; j < target.size(); j++) {
                String actual = lines.get(i + j);
                String expected = target.get(j);
                if (!actual.equals(expected) && !actual.trim().equals(expected.trim())) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }

    private boolean isWholeFileRewrite(Hunk hunk, List<String> fileLines) {
        if (hunk == null || hunk.newFile() || hunk.oldStartLine() > 1 || hunk.oldLines().isEmpty()) {
            return false;
        }
        int currentSize = fileLines == null ? 0 : fileLines.size();
        int oldSize = hunk.oldLines().size();
        if (oldSize < 8) {
            return false;
        }
        return Math.abs(oldSize - currentSize) <= 3
                || oldSize >= Math.max(1, currentSize * 8 / 10);
    }

    private CommandResult runGitApply(Path repoRoot, Path patchFile, boolean checkOnly) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("apply");
        command.add("--recount");
        command.add("--ignore-space-change");
        command.add("--ignore-whitespace");
        if (checkOnly) {
            command.add("--check");
        }
        command.add(patchFile.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repoRoot.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            boolean completed = process.waitFor(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new CommandResult(command, false, -1, "git apply timeout");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(command, process.exitValue() == 0, process.exitValue(), abbreviate(output, 4000));
        } catch (IOException e) {
            return new CommandResult(command, false, -1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(command, false, -1, "interrupted");
        }
    }

    private String normalizeUnifiedDiff(String patch) {
        if (patch == null || patch.trim().isEmpty()) {
            return "";
        }
        List<String> normalized = new ArrayList<>();
        boolean inHunk = false;
        for (String rawLine : patch.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine;
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.equals("PATCH_PROPOSAL_DRAFT") || trimmed.equals("PATCH_PROPOSAL")) {
                continue;
            }
            if (line.startsWith("diff --git ")
                    || line.startsWith("index ")
                    || line.startsWith("new file ")
                    || line.startsWith("deleted file ")
                    || line.startsWith("similarity index ")
                    || line.startsWith("rename from ")
                    || line.startsWith("rename to ")) {
                inHunk = false;
                normalized.add(line);
                continue;
            }
            if (line.startsWith("--- ") || line.startsWith("+++ ")) {
                inHunk = false;
                normalized.add(line);
                continue;
            }
            if (line.startsWith("@@")) {
                inHunk = true;
                normalized.add(line);
                continue;
            }
            if (line.startsWith("\\ No newline at end of file")) {
                normalized.add(line);
                continue;
            }
            if (inHunk && !(line.startsWith(" ") || line.startsWith("+") || line.startsWith("-"))) {
                normalized.add(" " + line);
                continue;
            }
            normalized.add(line);
        }
        while (!normalized.isEmpty() && normalized.get(normalized.size() - 1).isEmpty()) {
            normalized.remove(normalized.size() - 1);
        }
        return String.join("\n", normalized) + "\n";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record CommandResult(List<String> command, boolean success, int exitCode, String output) {
    }

    private record Hunk(int oldStartLine, List<String> oldLines, List<String> newLines, boolean newFile) {
    }

}
