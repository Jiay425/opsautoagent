package com.opsautoagent.domain.codeops.agent.patch;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PatchDiffAnalysisService {

    public PatchDiffAnalysisResult analyze(String patchText,
                                           PatchValidationResult validation,
                                           PatchScopeGuardResult guard) {
        Set<String> files = new LinkedHashSet<>();
        if (validation != null && validation.getTouchedFiles() != null) {
            files.addAll(validation.getTouchedFiles());
        }
        if (guard != null && guard.getTouchedFiles() != null) {
            files.addAll(guard.getTouchedFiles());
        }

        List<String> changedMethods = guard == null || guard.getChangedMethods() == null
                ? List.of() : guard.getChangedMethods();
        int productionFiles = 0;
        int testFiles = 0;
        int configFiles = 0;
        int scriptFiles = 0;
        List<String> sensitiveFiles = new ArrayList<>();
        for (String file : files) {
            String lower = normalize(file).toLowerCase(Locale.ROOT);
            if (lower.startsWith("src/test/")) {
                testFiles++;
            } else if (lower.startsWith("src/main/")) {
                productionFiles++;
            }
            if (isConfig(lower)) {
                configFiles++;
            }
            if (isScript(lower)) {
                scriptFiles++;
            }
            if (isSensitive(lower)) {
                sensitiveFiles.add(file);
            }
        }

        PatchStats stats = stats(patchText);
        List<String> warnings = new ArrayList<>();
        boolean scopeAligned = guard == null || guard.isPassed();
        if (!scopeAligned) {
            warnings.add("PatchScopeGuard did not pass; patch is not scope aligned.");
        }
        if (!sensitiveFiles.isEmpty()) {
            warnings.add("Patch touches sensitive file(s): " + String.join(", ", sensitiveFiles));
        }
        if (files.size() > 5) {
            warnings.add("Patch touches more than 5 files; review minimality.");
        }
        if (changedMethods.size() > 6) {
            warnings.add("Patch changes more than 6 methods; review scope.");
        }
        if (stats.additions + stats.deletions > 120) {
            warnings.add("Patch changes more than 120 lines; review minimality.");
        }

        int score = 100;
        score -= Math.max(0, files.size() - 2) * 10;
        score -= Math.max(0, changedMethods.size() - 3) * 8;
        score -= Math.max(0, stats.additions + stats.deletions - 60) / 3;
        score -= sensitiveFiles.size() * 35;
        score = Math.max(0, Math.min(100, score));

        return PatchDiffAnalysisResult.builder()
                .touchedFiles(new ArrayList<>(files))
                .changedMethods(changedMethods)
                .productionFileCount(productionFiles)
                .testFileCount(testFiles)
                .configFileCount(configFiles)
                .scriptFileCount(scriptFiles)
                .sensitiveFileCount(sensitiveFiles.size())
                .hunkCount(stats.hunkCount)
                .additions(stats.additions)
                .deletions(stats.deletions)
                .staticSafetyPassed(sensitiveFiles.isEmpty())
                .scopeAligned(scopeAligned)
                .testsChanged(testFiles > 0)
                .minimalChangeScore(score)
                .requiresHumanApproval(!sensitiveFiles.isEmpty() || score < 60)
                .sensitiveFiles(sensitiveFiles)
                .qualityWarnings(warnings)
                .build();
    }

    private PatchStats stats(String patchText) {
        int additions = 0;
        int deletions = 0;
        int hunkCount = 0;
        if (patchText == null || patchText.isBlank()) {
            return new PatchStats(0, 0, 0);
        }
        for (String line : patchText.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (line.startsWith("@@")) {
                hunkCount++;
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }
        return new PatchStats(additions, deletions, hunkCount);
    }

    private boolean isSensitive(String lowerPath) {
        return lowerPath.contains(".env")
                || lowerPath.equals("pom.xml")
                || lowerPath.endsWith("/pom.xml")
                || lowerPath.endsWith("build.gradle")
                || lowerPath.endsWith("settings.gradle")
                || isConfig(lowerPath)
                || isScript(lowerPath);
    }

    private boolean isConfig(String lowerPath) {
        return lowerPath.endsWith(".yml")
                || lowerPath.endsWith(".yaml")
                || lowerPath.endsWith(".properties")
                || lowerPath.endsWith(".xml")
                || lowerPath.contains("/config/");
    }

    private boolean isScript(String lowerPath) {
        return lowerPath.endsWith(".sh")
                || lowerPath.endsWith(".ps1")
                || lowerPath.endsWith(".bat")
                || lowerPath.endsWith(".cmd");
    }

    private String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private record PatchStats(int additions, int deletions, int hunkCount) {
    }
}
