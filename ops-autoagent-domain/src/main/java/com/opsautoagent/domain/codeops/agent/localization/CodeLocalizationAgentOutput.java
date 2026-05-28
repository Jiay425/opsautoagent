package com.opsautoagent.domain.codeops.agent.localization;

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
public class CodeLocalizationAgentOutput {

    private boolean success;

    private boolean fallback;

    private String confidence;

    private String strategyType;

    private boolean shouldEnterCodeRepair;

    private List<String> targetFiles;

    private List<String> targetMethods;

    private List<String> suspiciousLocations;

    private List<String> relatedTests;

    private List<String> reasoning;

    private List<String> missingEvidence;

    private String rawContent;

    private String errorMessage;

    private Long costMillis;

    private LocalDateTime createTime;

    public static CodeLocalizationAgentOutput unavailable(String reason) {
        return CodeLocalizationAgentOutput.builder()
                .success(false)
                .fallback(true)
                .confidence("LOW")
                .strategyType("NEED_MORE_EVIDENCE")
                .shouldEnterCodeRepair(false)
                .targetFiles(List.of())
                .targetMethods(List.of())
                .suspiciousLocations(List.of())
                .relatedTests(List.of())
                .reasoning(List.of())
                .missingEvidence(List.of(reason))
                .rawContent("")
                .errorMessage(reason)
                .costMillis(0L)
                .createTime(LocalDateTime.now())
                .build();
    }

}
