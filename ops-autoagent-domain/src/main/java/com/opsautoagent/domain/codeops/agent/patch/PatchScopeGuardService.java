package com.opsautoagent.domain.codeops.agent.patch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PatchScopeGuardService {

    public PatchScopeGuardResult validate(String repositoryPath,
                                           String patchText,
                                           List<FileRewritePatchEntity> fileRewrites,
                                           Map<String, Object> repairScope) {
        if (repairScope == null || repairScope.isEmpty()) {
            return PatchScopeGuardResult.passed(List.of(), List.of(), repairScope);
        }

        String scopeType = String.valueOf(repairScope.getOrDefault("scopeType", "FULL_FILE"));

        // --- NO_CODE_FIX: must have zero patch content ---
        if ("NO_CODE_FIX".equals(scopeType)) {
            boolean hasPatch = !isBlank(patchText);
            boolean hasRewrites = fileRewrites != null && !fileRewrites.isEmpty();
            if (hasPatch || hasRewrites) {
                return PatchScopeGuardResult.failed(
                        "NO_CODE_FIX_PATCH",
                        List.of(),
                        List.of(),
                        List.of("NO_CODE_FIX scope prohibits any patch. patchText present=" + hasPatch
                                + ", fileRewrites count=" + (fileRewrites == null ? 0 : fileRewrites.size())),
                        repairScope
                );
            }
            return PatchScopeGuardResult.passed(List.of(), List.of(), repairScope);
        }

        // --- Collect touched files ---
        @SuppressWarnings("unchecked")
        List<String> scopeTargetFiles = repairScope.get("targetFiles") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        @SuppressWarnings("unchecked")
        List<String> scopeTargetMethods = repairScope.get("targetMethods") instanceof List<?> list2
                ? list2.stream().map(String::valueOf).toList() : List.of();

        Set<String> touchedFiles = new LinkedHashSet<>();
        if (fileRewrites != null) {
            for (FileRewritePatchEntity rewrite : fileRewrites) {
                if (rewrite != null && !isBlank(rewrite.getFilePath())) {
                    touchedFiles.add(normalizePath(rewrite.getFilePath()));
                }
            }
        }
        if (!isBlank(patchText)) {
            touchedFiles.addAll(extractFilesFromPatch(patchText));
        }

        // --- Detect changed methods ---
        List<String> changedMethods = new ArrayList<>();
        if (!isBlank(repositoryPath)) {
            Path repo = Path.of(repositoryPath).toAbsolutePath().normalize();
            for (String file : touchedFiles) {
                Path filePath = repo.resolve(file).normalize();
                if (!filePath.startsWith(repo) || !Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                    continue;
                }
                try {
                    String oldContent = normalizeLineEndings(Files.readString(filePath, StandardCharsets.UTF_8));
                    String newContent = oldContent;
                    if (fileRewrites != null) {
                        for (FileRewritePatchEntity rewrite : fileRewrites) {
                            if (file.equals(normalizePath(rewrite.getFilePath()))
                                    && !isBlank(rewrite.getNewContent())) {
                                newContent = normalizeLineEndings(rewrite.getNewContent());
                                break;
                            }
                        }
                    } else if (!isBlank(patchText)) {
                        // unifiedDiffPatch without fileRewrites: apply in-memory
                        newContent = applyPatchToContent(oldContent, patchText, file);
                        if (newContent == null || newContent.equals(oldContent)) {
                            log.warn("PatchScopeGuard: unifiedDiffPatch could not be applied in-memory for {}, "
                                    + "changedMethods detection may be unreliable. file={}", file, file);
                            // Fall through with oldContent — guard will detect no changes
                        }
                    }
                    changedMethods.addAll(detectChangedMethods(file, oldContent, newContent, patchText));
                } catch (IOException ignored) {
                }
            }
        }

        // --- Enforce scope rules ---
        List<String> violations = new ArrayList<>();

        // For STRICT_SINGLE_METHOD / MULTI_METHOD:
        // If patch is non-empty but we detected NO touched files and NO changed methods,
        // the detection is unreliable — fail safe.
        boolean hasPatchContent = !isBlank(patchText) || (fileRewrites != null && !fileRewrites.isEmpty());
        if (!"NO_CODE_FIX".equals(scopeType) && !"FULL_FILE".equals(scopeType) && hasPatchContent) {
            if (touchedFiles.isEmpty() && changedMethods.isEmpty()) {
                violations.add("DETECTION_FAILED: Cannot detect touchedFiles or changedMethods from the patch. "
                        + "For STRICT_SINGLE_METHOD / MULTI_METHOD scope, use fileRewrites with complete file content. "
                        + "unifiedDiffPatch-only input is not reliably scoped.");
            }
        }

        // For STRICT_SINGLE_METHOD / MULTI_METHOD with unifiedDiffPatch only:
        // if no changedMethods detected but patch is non-empty, the in-memory apply may have failed.
        if (!"NO_CODE_FIX".equals(scopeType) && !"FULL_FILE".equals(scopeType)
                && !isBlank(patchText)
                && (fileRewrites == null || fileRewrites.isEmpty())
                && changedMethods.isEmpty()) {
            violations.add("UNIFIED_DIFF_ONLY: Cannot reliably detect changedMethods from unifiedDiffPatch. "
                    + "For STRICT_SINGLE_METHOD / MULTI_METHOD scope, use fileRewrites instead.");
        }

        // Check touched files are in scope
        for (String touched : touchedFiles) {
            boolean inScope = scopeTargetFiles.isEmpty() || scopeTargetFiles.stream()
                    .anyMatch(scopeFile -> normalizePath(scopeFile).equals(touched)
                            || normalizePath(scopeFile).endsWith("/" + touched)
                            || touched.endsWith("/" + normalizePath(scopeFile)));
            if (!inScope && ("STRICT_SINGLE_METHOD".equals(scopeType) || "MULTI_METHOD".equals(scopeType))) {
                violations.add("TOUCHED_FILE_OUT_OF_SCOPE: " + touched
                        + " not in repairScope.targetFiles " + scopeTargetFiles);
            }
        }

        violations.addAll(validateExpansionBoundary(repairScope, scopeTargetFiles, scopeTargetMethods));

        // Check changed methods are in scope
        if ("STRICT_SINGLE_METHOD".equals(scopeType)) {
            for (String method : changedMethods) {
                if (isStructuralChange(method)) {
                    continue; // imports/fields/constructors tolerated
                }
                boolean inScope = scopeTargetMethods.isEmpty() || scopeTargetMethods.stream()
                        .anyMatch(target -> method.equals(target)
                                || method.endsWith("." + target)
                                || target.endsWith("." + method));
                if (!inScope) {
                    violations.add("METHOD_OUT_OF_SCOPE: STRICT_SINGLE_METHOD only allows "
                            + scopeTargetMethods + ", but patch changed " + method);
                }
            }
        } else if ("MULTI_METHOD".equals(scopeType)) {
            for (String method : changedMethods) {
                if (isStructuralChange(method)) {
                    continue;
                }
                boolean inScope = scopeTargetMethods.isEmpty() || scopeTargetMethods.stream()
                        .anyMatch(target -> method.equals(target)
                                || method.endsWith("." + target)
                                || target.endsWith("." + method));
                if (!inScope) {
                    violations.add("METHOD_OUT_OF_SCOPE: MULTI_METHOD allows "
                            + scopeTargetMethods + ", but patch changed non-target method " + method);
                }
            }
        }

        // Verify repairScope targetMethods actually exist in the code
        if (!scopeTargetMethods.isEmpty() && !isBlank(repositoryPath)) {
            List<String> filesToCheck = touchedFiles.isEmpty() ? scopeTargetFiles : new ArrayList<>(touchedFiles);
            List<String> nonexistentMethods = verifyMethodsExist(repositoryPath,
                    filesToCheck, scopeTargetMethods);
            if (!nonexistentMethods.isEmpty()) {
                violations.add("HALLUCINATED_SCOPE: targetMethods not found in source files: "
                        + nonexistentMethods + ". repairScope may be hallucinated — "
                        + "downgrade confidence or verify CodeLocalization output.");
            }
        }

        if (!violations.isEmpty()) {
            String failureType = violations.stream().anyMatch(v -> v.contains("TOUCHED_FILE_OUT_OF_SCOPE"))
                    ? "TOUCHED_FILE_OUT_OF_SCOPE"
                    : (violations.stream().anyMatch(v -> v.contains("SCOPE_EXPANSION_OUT_OF_BOUND"))
                        ? "SCOPE_EXPANSION_OUT_OF_BOUND"
                    : (violations.stream().anyMatch(v -> v.contains("HALLUCINATED_SCOPE"))
                        ? "HALLUCINATED_SCOPE" : "METHOD_OUT_OF_SCOPE"));
            return PatchScopeGuardResult.failed(failureType,
                    new ArrayList<>(touchedFiles), changedMethods, violations, repairScope);
        }

        return PatchScopeGuardResult.passed(new ArrayList<>(touchedFiles), changedMethods, repairScope);
    }

    // --- Method change detection ---

    private List<String> detectChangedMethods(String filePath, String oldContent, String newContent, String patchText) {
        String className = simpleClassName(filePath);
        List<String> changed = new ArrayList<>();

        // Normalize line endings for comparison
        oldContent = normalizeLineEndings(oldContent);
        newContent = normalizeLineEndings(newContent);

        // If no actual change, return empty
        if (oldContent.equals(newContent) && isBlank(patchText)) {
            return changed;
        }

        // Parse method regions from old content
        Map<String, int[]> oldMethods = parseMethodRegions(oldContent);
        Map<String, int[]> newMethods = parseMethodRegions(newContent);

        // Check which old methods have content changes
        List<String> oldLines = splitLines(oldContent);
        List<String> newLines = splitLines(newContent);

        for (Map.Entry<String, int[]> entry : oldMethods.entrySet()) {
            String methodName = entry.getKey();
            int[] region = entry.getValue();
            int startLine = region[0];
            int endLine = region[1];

            String oldMethodBody = extractLines(oldLines, startLine, endLine);
            String newMethodBody = "";

            int[] newRegion = newMethods.get(methodName);
            if (newRegion != null) {
                newMethodBody = extractLines(newLines, newRegion[0], newRegion[1]);
            }

            if (!oldMethodBody.equals(newMethodBody)) {
                changed.add(className + "." + methodName);
            }
        }

        // Check for new methods in newContent not in oldContent
        for (String newMethod : newMethods.keySet()) {
            if (!oldMethods.containsKey(newMethod)) {
                changed.add(className + "." + newMethod);
            }
        }

        // If no method-level changes detected but content changed,
        // it's likely import/field/constructor changes
        if (changed.isEmpty() && !oldContent.equals(newContent)) {
            changed.add(className + ".<IMPORT_OR_FIELD>");
        }

        return changed;
    }

    private Map<String, int[]> parseMethodRegions(String content) {
        Map<String, int[]> methods = new LinkedHashMap<>();
        List<String> lines = splitLines(content);
        Pattern methodSig = Pattern.compile(
                "(public|protected|private|static|\\s)*[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w\\s,]+)?\\s*\\{?");

        int braceDepth = 0;
        String currentMethod = null;
        int methodStart = -1;
        int classBraceDepth = -1;
        boolean inClass = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // Track class-level brace depth
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    if (classBraceDepth < 0 && !inClass) {
                        inClass = true;
                        classBraceDepth = 1;
                    } else if (inClass) {
                        braceDepth++;
                    }
                } else if (c == '}') {
                    if (inClass && braceDepth > 0) {
                        braceDepth--;
                    }
                }
            }

            // Skip non-class lines
            if (!inClass) {
                continue;
            }

            // Skip lines inside a method body
            if (currentMethod != null && braceDepth > 0) {
                continue;
            }

            // Look for method signature
            Matcher m = methodSig.matcher(line);
            if (m.find() && !line.contains(" class ") && !line.contains(" interface ")
                    && !line.contains(" enum ") && !line.contains(" record ")) {
                String name = m.group(2);
                // Skip common non-method patterns
                if (name.equals("if") || name.equals("for") || name.equals("while")
                        || name.equals("switch") || name.equals("try") || name.equals("catch")
                        || name.equals("finally") || name.equals("synchronized") || name.equals("new")
                        || name.equals("return") || name.equals("throw") || name.equals("class")
                        || name.equals("package") || name.equals("import")) {
                    continue;
                }
                currentMethod = name;
                methodStart = i;
                // Count opening braces on this line
                braceDepth = countBraces(line);
                if (braceDepth == 0 && !line.endsWith("{")) {
                    // Method signature spans multiple lines, brace on next line
                }
                if (braceDepth > 0 && line.endsWith("{")) {
                    // Method body starts here with opening brace
                }
            }

            // Detect method end
            if (currentMethod != null && braceDepth == 0 && i > methodStart) {
                methods.put(currentMethod, new int[]{methodStart, i});
                currentMethod = null;
                methodStart = -1;
            }
        }

        // Last method extends to end of file
        if (currentMethod != null) {
            methods.put(currentMethod, new int[]{methodStart, lines.size() - 1});
        }

        return methods;
    }

    private int countBraces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == '{') count++;
            else if (c == '}') count--;
        }
        return count;
    }

    private String extractLines(List<String> lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end && i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    private boolean isStructuralChange(String method) {
        return method.endsWith(".<IMPORT_OR_FIELD>") || method.endsWith(".<CONSTRUCTOR>");
    }

    @SuppressWarnings("unchecked")
    private List<String> validateExpansionBoundary(Map<String, Object> repairScope,
                                                   List<String> scopeTargetFiles,
                                                   List<String> scopeTargetMethods) {
        if (repairScope == null || repairScope.isEmpty()) {
            return List.of();
        }
        Object decisionObj = repairScope.get("scopeDecision");
        if (!(decisionObj instanceof Map<?, ?> decisionMap)) {
            return List.of();
        }
        Object decisionValue = decisionMap.get("decision");
        String decision = decisionValue == null ? "KEEP_SCOPE" : String.valueOf(decisionValue);
        if (!"EXPAND_SCOPE".equals(decision)) {
            return List.of();
        }
        Object candidateObj = repairScope.get("candidateScope");
        if (!(candidateObj instanceof Map<?, ?> candidateScope)) {
            return List.of("SCOPE_EXPANSION_OUT_OF_BOUND: EXPAND_SCOPE requested but repairScope.candidateScope is missing.");
        }
        Object expandableValue = candidateScope.get("expandable");
        boolean expandable = expandableValue != null && Boolean.parseBoolean(String.valueOf(expandableValue));
        if (!expandable) {
            return List.of("SCOPE_EXPANSION_OUT_OF_BOUND: EXPAND_SCOPE requested but candidateScope.expandable=false.");
        }

        List<String> candidateFiles = candidateScope.get("targetFiles") instanceof List<?> fileList
                ? fileList.stream().map(String::valueOf).map(this::normalizePath).toList() : List.of();
        List<String> candidateMethods = candidateScope.get("targetMethods") instanceof List<?> methodList
                ? methodList.stream().map(String::valueOf).toList() : List.of();
        List<String> violations = new ArrayList<>();

        if (!scopeTargetMethods.isEmpty() && candidateMethods.isEmpty()
                && !"FULL_FILE".equals(String.valueOf(repairScope.getOrDefault("scopeType", "")))) {
            violations.add("SCOPE_EXPANSION_OUT_OF_BOUND: EXPAND_SCOPE requested for method-level repair, "
                    + "but candidateScope.targetMethods is empty.");
        }

        for (String file : scopeTargetFiles) {
            String normalized = normalizePath(file);
            boolean inCandidate = candidateFiles.isEmpty() || candidateFiles.stream()
                    .anyMatch(candidate -> normalized.equals(candidate)
                            || normalized.endsWith("/" + candidate)
                            || candidate.endsWith("/" + normalized));
            if (!inCandidate) {
                violations.add("SCOPE_EXPANSION_OUT_OF_BOUND: final target file " + file
                        + " is outside candidateScope.targetFiles " + candidateFiles);
            }
        }

        for (String method : scopeTargetMethods) {
            boolean inCandidate = candidateMethods.isEmpty() || candidateMethods.stream()
                    .anyMatch(candidate -> method.equals(candidate)
                            || method.endsWith("." + candidate)
                            || candidate.endsWith("." + method));
            if (!inCandidate) {
                violations.add("SCOPE_EXPANSION_OUT_OF_BOUND: final target method " + method
                        + " is outside candidateScope.targetMethods " + candidateMethods);
            }
        }
        return violations;
    }

    // --- File extraction from patch text ---

    private List<String> extractFilesFromPatch(String patchText) {
        Set<String> files = new LinkedHashSet<>();
        String normalized = (patchText == null ? "" : patchText).replace("\r\n", "\n").replace('\r', '\n');
        Pattern filePattern = Pattern.compile("^[-+]{3}\\s+[ab]/(.+)$", Pattern.MULTILINE);
        Matcher m = filePattern.matcher(normalized);
        while (m.find()) {
            String file = m.group(1).trim();
            if (file.endsWith(".java")) {
                files.add(normalizePath(file));
            }
        }
        return new ArrayList<>(files);
    }

    // --- Utilities ---

    private String simpleClassName(String filePath) {
        if (isBlank(filePath)) return "";
        String name = filePath.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
        return name;
    }

    /**
     * Check if targetMethods actually exist in the source files.
     * Returns list of methods that could NOT be found — empty = all good.
     */
    private List<String> verifyMethodsExist(String repositoryPath, List<String> files, List<String> methods) {
        List<String> nonexistent = new ArrayList<>();
        if (methods.isEmpty() || files.isEmpty()) return nonexistent;

        Path repo = Path.of(repositoryPath).toAbsolutePath().normalize();
        for (String method : methods) {
            boolean found = false;
            String shortName = method.contains(".") ? method.substring(method.lastIndexOf('.') + 1) : method;
            for (String file : files) {
                Path filePath = repo.resolve(normalizePath(file)).normalize();
                if (!filePath.startsWith(repo) || !Files.exists(filePath)) continue;
                try {
                    String content = normalizeLineEndings(Files.readString(filePath, StandardCharsets.UTF_8));
                    // Check if method signature exists
                    if (content.contains(shortName + "(")) {
                        found = true;
                        break;
                    }
                } catch (IOException ignored) {}
            }
            if (!found) nonexistent.add(method);
        }
        return nonexistent;
    }

    private String normalizePath(String path) {
        if (isBlank(path)) return "";
        return path.replace('\\', '/');
    }

    private String normalizeLineEndings(String content) {
        if (isBlank(content)) return "";
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private List<String> splitLines(String content) {
        if (isBlank(content)) return List.of();
        return List.of(normalizeLineEndings(content).split("\n", -1));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Apply a unified diff patch to content in-memory, returning the patched content.
     * Handles simple single-hunk patches. Returns null if the patch cannot be applied.
     */
    private String applyPatchToContent(String original, String patchText, String fileName) {
        if (isBlank(original) || isBlank(patchText)) {
            return original;
        }
        List<String> oldLines = splitLines(original);
        // Ensure final empty line is preserved
        if (original.endsWith("\n")) {
            oldLines = new ArrayList<>(oldLines);
            oldLines.add("");
        }

        // Parse unified diff: find the @@ hunk header and extract context/removed/added lines
        java.util.regex.Pattern hunkPattern = java.util.regex.Pattern.compile(
                "@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@\\s*(.*?)(?=\\n@@|\\n---|\\Z)",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = hunkPattern.matcher(patchText);

        StringBuilder result = new StringBuilder();
        int currentLine = 0; // 0-indexed position in oldLines

        while (m.find()) {
            int oldStart = Integer.parseInt(m.group(1)) - 1; // convert to 0-indexed
            int oldCount = m.group(2) != null ? Integer.parseInt(m.group(2)) : oldLines.size() - oldStart;

            // Copy lines before the hunk
            while (currentLine < oldStart && currentLine < oldLines.size()) {
                result.append(oldLines.get(currentLine)).append('\n');
                currentLine++;
            }

            // Process hunk body lines
            String hunkBody = m.group(5);
            List<String> hunkLines = splitLines(hunkBody);
            int hunkOldPos = 0;

            for (String hunkLine : hunkLines) {
                if (hunkLine.startsWith(" ")) {
                    // Context line: must match
                    String expected = hunkLine.length() > 1 ? hunkLine.substring(1) : "";
                    if (currentLine < oldLines.size() && oldLines.get(currentLine).equals(expected)) {
                        result.append(oldLines.get(currentLine)).append('\n');
                        currentLine++;
                    } else {
                        // Context mismatch — can't apply
                        return null;
                    }
                    hunkOldPos++;
                } else if (hunkLine.startsWith("-")) {
                    // Removed line: skip in old content
                    if (currentLine < oldLines.size()) {
                        currentLine++;
                    }
                    hunkOldPos++;
                } else if (hunkLine.startsWith("+")) {
                    // Added line: append to result
                    String added = hunkLine.length() > 1 ? hunkLine.substring(1) : "";
                    result.append(added).append('\n');
                } else {
                    // Empty line or continuation — treat as context
                    result.append(hunkLine).append('\n');
                    if (currentLine < oldLines.size()) {
                        currentLine++;
                    }
                }
            }

            // Skip remaining old lines in the hunk range
            while (hunkOldPos < oldCount && currentLine < oldLines.size()) {
                currentLine++;
                hunkOldPos++;
            }
        }

        // Copy remaining lines after the last hunk
        while (currentLine < oldLines.size()) {
            result.append(oldLines.get(currentLine)).append('\n');
            currentLine++;
        }

        return result.toString();
    }
}
