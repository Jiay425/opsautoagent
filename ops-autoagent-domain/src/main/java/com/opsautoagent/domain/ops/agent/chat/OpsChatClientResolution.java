package com.opsautoagent.domain.ops.agent.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsChatClientResolution {

    private OpsChatAgentRole role;

    private boolean available;

    private boolean fallback;

    private String beanName;

    private String source;

    private String message;

    private ChatClient chatClient;

    public static OpsChatClientResolution resolved(OpsChatAgentRole role,
                                                   ChatClient chatClient,
                                                   String beanName,
                                                   String source,
                                                   boolean fallback,
                                                   String message) {
        return OpsChatClientResolution.builder()
                .role(role)
                .available(true)
                .fallback(fallback)
                .beanName(beanName)
                .source(source)
                .message(message)
                .chatClient(chatClient)
                .build();
    }

    public static OpsChatClientResolution unavailable(OpsChatAgentRole role, String message) {
        return OpsChatClientResolution.builder()
                .role(role)
                .available(false)
                .fallback(true)
                .source("UNAVAILABLE")
                .message(message)
                .build();
    }

}

