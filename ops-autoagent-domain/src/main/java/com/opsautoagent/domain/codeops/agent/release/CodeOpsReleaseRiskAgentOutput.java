package com.opsautoagent.domain.codeops.agent.release;

import com.opsautoagent.domain.codeops.model.entity.ReleaseRiskReportEntity;
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
public class CodeOpsReleaseRiskAgentOutput {

    private boolean success;

    private boolean fallback;

    private ReleaseRiskReportEntity report;

    private List<String> reasoning;

    private List<String> humanApprovalPoints;

    private Map<String, Object> codeReview;

    private Map<String, Object> modelRouting;

    private String reviewVerdict;

    private Integer qualityScore;

    private String patchDecision;

    private String rawContent;

    private String errorMessage;

    private Long costMillis;

    private LocalDateTime createTime;

    public static CodeOpsReleaseRiskAgentOutput unavailable(String reason, ReleaseRiskReportEntity baselineReport) {
        return CodeOpsReleaseRiskAgentOutput.builder()
                .success(false)
                .fallback(true)
                .report(baselineReport)
                .reasoning(List.of())
                .humanApprovalPoints(List.of(reason))
                .codeReview(Map.of(
                        "reviewVerdict", "REVIEW_UNAVAILABLE",
                        "qualityScore", 0,
                        "patchDecision", "HUMAN_REVIEW",
                        "reason", reason
                ))
                .reviewVerdict("REVIEW_UNAVAILABLE")
                .qualityScore(0)
                .patchDecision("HUMAN_REVIEW")
                .rawContent("")
                .errorMessage(reason)
                .costMillis(0L)
                .createTime(LocalDateTime.now())
                .build();
    }

}
