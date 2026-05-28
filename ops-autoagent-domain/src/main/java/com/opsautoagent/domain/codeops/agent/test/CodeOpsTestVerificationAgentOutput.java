package com.opsautoagent.domain.codeops.agent.test;

import com.opsautoagent.domain.codeops.model.entity.TestVerificationPlanEntity;
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
public class CodeOpsTestVerificationAgentOutput {

    private boolean success;

    private boolean fallback;

    private TestVerificationPlanEntity plan;

    private List<String> reasoning;

    private String rawContent;

    private String errorMessage;

    private Long costMillis;

    private LocalDateTime createTime;

    public static CodeOpsTestVerificationAgentOutput unavailable(String reason, TestVerificationPlanEntity baselinePlan) {
        return CodeOpsTestVerificationAgentOutput.builder()
                .success(false)
                .fallback(true)
                .plan(baselinePlan)
                .reasoning(List.of(reason))
                .rawContent("")
                .errorMessage(reason)
                .costMillis(0L)
                .createTime(LocalDateTime.now())
                .build();
    }

}
