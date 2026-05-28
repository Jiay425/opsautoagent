package com.opsautoagent.domain.codeops.agent.testpatch;

import com.opsautoagent.domain.codeops.agent.patch.FileRewritePatchEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsTestPatchAgentOutput {

    private boolean success;

    private boolean fallback;

    private List<String> targetTestFiles;

    private List<String> reasoning;

    private String unifiedDiffPatch;

    private List<FileRewritePatchEntity> fileRewrites;

    private String rawContent;

    private String errorMessage;

    private Long costMillis;

    private LocalDateTime createTime;

    public static CodeOpsTestPatchAgentOutput unavailable(String reason) {
        return CodeOpsTestPatchAgentOutput.builder()
                .success(false)
                .fallback(true)
                .targetTestFiles(List.of())
                .reasoning(List.of(reason))
                .unifiedDiffPatch("")
                .fileRewrites(List.of())
                .rawContent("")
                .errorMessage(reason)
                .costMillis(0L)
                .createTime(LocalDateTime.now())
                .build();
    }

}
