package com.opsautoagent.domain.codeops.agent.patch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PatchDiffAnalysisResult {

    private List<String> touchedFiles;

    private List<String> changedMethods;

    private int productionFileCount;

    private int testFileCount;

    private int configFileCount;

    private int scriptFileCount;

    private int sensitiveFileCount;

    private int hunkCount;

    private int additions;

    private int deletions;

    private boolean staticSafetyPassed;

    private boolean scopeAligned;

    private boolean testsChanged;

    private int minimalChangeScore;

    private boolean requiresHumanApproval;

    private List<String> sensitiveFiles;

    private List<String> qualityWarnings;

    public Map<String, Object> toRawOutput() {
        return Map.ofEntries(
                Map.entry("touchedFiles", touchedFiles == null ? List.of() : touchedFiles),
                Map.entry("changedMethods", changedMethods == null ? List.of() : changedMethods),
                Map.entry("productionFileCount", productionFileCount),
                Map.entry("testFileCount", testFileCount),
                Map.entry("configFileCount", configFileCount),
                Map.entry("scriptFileCount", scriptFileCount),
                Map.entry("sensitiveFileCount", sensitiveFileCount),
                Map.entry("hunkCount", hunkCount),
                Map.entry("additions", additions),
                Map.entry("deletions", deletions),
                Map.entry("staticSafetyPassed", staticSafetyPassed),
                Map.entry("scopeAligned", scopeAligned),
                Map.entry("testsChanged", testsChanged),
                Map.entry("minimalChangeScore", minimalChangeScore),
                Map.entry("requiresHumanApproval", requiresHumanApproval),
                Map.entry("sensitiveFiles", sensitiveFiles == null ? List.of() : sensitiveFiles),
                Map.entry("qualityWarnings", qualityWarnings == null ? List.of() : qualityWarnings)
        );
    }
}
