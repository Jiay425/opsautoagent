package com.opsautoagent.domain.codeops.agent.review;

import com.opsautoagent.domain.codeops.model.entity.ReviewFindingEntity;
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
public class CodeOpsReviewAgentOutput {

    private boolean success;

    private boolean fallback;

    private String summary;

    private List<ReviewFindingEntity> findings;

    private List<String> reviewNotes;

    private String rawContent;

    private String errorMessage;

    private Long costMillis;

    private LocalDateTime createTime;

    public static CodeOpsReviewAgentOutput unavailable(String reason) {
        return CodeOpsReviewAgentOutput.builder()
                .success(false)
                .fallback(true)
                .summary("")
                .findings(List.of())
                .reviewNotes(List.of())
                .rawContent("")
                .errorMessage(reason)
                .costMillis(0L)
                .createTime(LocalDateTime.now())
                .build();
    }

}
