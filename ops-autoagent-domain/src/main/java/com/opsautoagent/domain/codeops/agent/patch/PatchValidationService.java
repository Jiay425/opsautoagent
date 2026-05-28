package com.opsautoagent.domain.codeops.agent.patch;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PatchValidationService {

    public PatchValidationResult validate(String repositoryPath, String unifiedDiffPatch) {
        unifiedDiffPatch = normalizeUnifiedDiff(unifiedDiffPatch);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> touchedFiles = new LinkedHashSet<>();

        if (isBlank(unifiedDiffPatch)) {
            return PatchValidationResult.builder()
                    .patchPresent(false)
                    .valid(false)
                    .repositoryPath(value(repositoryPath))
                    .touchedFiles(List.of())
                    .existingTouchedFiles(List.of())
                    .missingTouchedFiles(List.of())
                    .errors(List.of("patch is empty"))
                    .warnings(List.of())
                    .build();
        }

        boolean hasOldHeader = false;
        boolean hasNewHeader = false;
        boolean hasHunk = false;
        boolean touchesDevNull = false;
        for (String line : unifiedDiffPatch.split("\\R")) {
            if (line.startsWith("--- ")) {
                hasOldHeader = true;
                String file = extractDiffPath(line.substring(4).trim());
                if ("/dev/null".equals(file)) {
                    touchesDevNull = true;
                } else if (!isBlank(file)) {
                    touchedFiles.add(file);
                }
            } else if (line.startsWith("+++ ")) {
                hasNewHeader = true;
                String file = extractDiffPath(line.substring(4).trim());
                if ("/dev/null".equals(file)) {
                    touchesDevNull = true;
                } else if (!isBlank(file)) {
                    touchedFiles.add(file);
                }
            } else if (line.startsWith("@@")) {
                hasHunk = true;
            } else if (line.startsWith("diff --git ")) {
                extractGitDiffPaths(line, touchedFiles);
            }
        }

        if (!hasOldHeader || !hasNewHeader) {
            errors.add("unified diff must contain both --- and +++ file headers");
        }
        if (!hasHunk) {
            warnings.add("patch does not contain a standard @@ hunk header");
        }
        if (touchesDevNull && !onlyTouchesTestFiles(touchedFiles)) {
            errors.add("patch references /dev/null; creating or deleting files is not allowed in incident fix proposal");
        }
        if (touchedFiles.isEmpty()) {
            errors.add("patch does not contain any touched file path");
        }

        Path repoRoot = resolveRepositoryPath(repositoryPath);
        List<String> existingTouchedFiles = new ArrayList<>();
        List<String> missingTouchedFiles = new ArrayList<>();
        if (repoRoot == null) {
            errors.add("repository path is empty");
        } else if (!Files.exists(repoRoot)) {
            errors.add("repository path does not exist: " + repoRoot);
        } else {
            for (String touchedFile : touchedFiles) {
                Path filePath = repoRoot.resolve(touchedFile).normalize();
                if (!filePath.startsWith(repoRoot.normalize())) {
                    errors.add("patch path escapes repository: " + touchedFile);
                } else if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    existingTouchedFiles.add(touchedFile);
                } else {
                    missingTouchedFiles.add(touchedFile);
                }
            }
        }

        if (!touchedFiles.isEmpty()
                && existingTouchedFiles.isEmpty()
                && !(touchesDevNull && onlyTouchesTestFiles(touchedFiles))) {
            errors.add("patch does not reference an existing repository file");
        }
        if (!missingTouchedFiles.isEmpty()) {
            warnings.add("patch references files not found in repository: " + String.join(", ", missingTouchedFiles));
        }

        return PatchValidationResult.builder()
                .patchPresent(true)
                .valid(errors.isEmpty())
                .repositoryPath(value(repositoryPath))
                .touchedFiles(List.copyOf(touchedFiles))
                .existingTouchedFiles(existingTouchedFiles)
                .missingTouchedFiles(missingTouchedFiles)
                .errors(errors)
                .warnings(warnings)
                .build();
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

    private void extractGitDiffPaths(String line, Set<String> touchedFiles) {
        String[] parts = line.split("\\s+");
        if (parts.length < 4) {
            return;
        }
        String oldPath = extractDiffPath(parts[2]);
        String newPath = extractDiffPath(parts[3]);
        if (!isBlank(oldPath) && !"/dev/null".equals(oldPath)) {
            touchedFiles.add(oldPath);
        }
        if (!isBlank(newPath) && !"/dev/null".equals(newPath)) {
            touchedFiles.add(newPath);
        }
    }

    private String extractDiffPath(String rawPath) {
        if (isBlank(rawPath)) {
            return "";
        }
        String path = rawPath.split("\\s+", 2)[0].replace('\\', '/');
        if (path.startsWith("\"") && path.endsWith("\"") && path.length() > 1) {
            path = path.substring(1, path.length() - 1);
        }
        if ("/dev/null".equals(path)) {
            return path;
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            path = path.substring(2);
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path;
    }

    private boolean onlyTouchesTestFiles(Set<String> touchedFiles) {
        if (touchedFiles == null || touchedFiles.isEmpty()) {
            return false;
        }
        for (String touchedFile : touchedFiles) {
            String normalized = touchedFile == null ? "" : touchedFile.replace('\\', '/');
            if (!normalized.startsWith("src/test/")) {
                return false;
            }
        }
        return true;
    }

    private Path resolveRepositoryPath(String repositoryPath) {
        if (isBlank(repositoryPath)) {
            return null;
        }
        return Paths.get(repositoryPath).toAbsolutePath().normalize();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

}
