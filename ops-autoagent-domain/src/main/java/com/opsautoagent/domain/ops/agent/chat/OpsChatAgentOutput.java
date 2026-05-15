package com.opsautoagent.domain.ops.agent.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsChatAgentOutput {

    private OpsChatAgentRole role;

    private boolean success;

    private boolean fallback;

    private String content;

    private String rawContent;

    private String errorMessage;

    private String clientBeanName;

    private String resolutionSource;

    private Long costMillis;

    private LocalDateTime createTime;

    public static OpsChatAgentOutput unavailable(OpsChatAgentRole role, String reason) {
        return OpsChatAgentOutput.builder()
                .role(role)
                .success(false)
                .fallback(true)
                .content("")
                .rawContent("")
                .errorMessage(reason)
                .resolutionSource("UNAVAILABLE")
                .costMillis(0L)
                .createTime(LocalDateTime.now())
                .build();
    }

}

