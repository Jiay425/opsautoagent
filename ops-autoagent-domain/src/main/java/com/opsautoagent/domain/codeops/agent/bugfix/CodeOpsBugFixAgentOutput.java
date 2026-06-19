package com.opsautoagent.domain.codeops.agent.bugfix;

import com.opsautoagent.domain.codeops.agent.patch.FileRewritePatchEntity;
import com.opsautoagent.domain.codeops.agent.patch.ExactReplaceBlockPatchEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsBugFixAgentOutput {

    private boolean success;

    private boolean fallback;

    private String rootCause;

    private String confidence;

    private List<String> targetFiles;

    private List<String> reasoning;

    private Map<String, Object> reflectionDiagnosis;

    private Map<String, Object> scopeDecision;

    private String unifiedDiffPatch;

    private List<FileRewritePatchEntity> fileRewrites;

    private List<ExactReplaceBlockPatchEntity> exactReplaceBlocks;

    private List<String> testSuggestions;

    private List<String> mavenCommands;

    private String testUnifiedDiffPatch;

    private List<FileRewritePatchEntity> testFileRewrites;

    private List<String> riskNotes;

    private String rawContent;

    private String errorMessage;

    private Long costMillis;

    private LocalDateTime createTime;

    private Map<String, Object> modelRouting;

    private Map<String, Object> llmUsage;

    public static CodeOpsBugFixAgentOutput unavailable(String reason) {
        return CodeOpsBugFixAgentOutput.builder()
                .success(false)
                .fallback(true)
                .rootCause("")
                .confidence("LOW")
                .targetFiles(List.of())
                .reasoning(List.of())
                .reflectionDiagnosis(Map.of())
                .scopeDecision(Map.of())
                .unifiedDiffPatch("")
                .fileRewrites(List.of())
                .exactReplaceBlocks(List.of())
                .testSuggestions(List.of())
                .mavenCommands(List.of())
                .testUnifiedDiffPatch("")
                .testFileRewrites(List.of())
                .riskNotes(List.of())
                .rawContent("")
                .errorMessage(reason)
                .costMillis(0L)
                .createTime(LocalDateTime.now())
                .llmUsage(Map.of())
                .build();
    }

}
