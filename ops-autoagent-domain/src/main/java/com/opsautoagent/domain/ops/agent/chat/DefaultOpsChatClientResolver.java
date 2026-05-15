package com.opsautoagent.domain.ops.agent.chat;

import com.opsautoagent.domain.agent.model.valobj.enums.AiAgentEnumVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DefaultOpsChatClientResolver implements OpsChatClientResolver {

    @Resource
    private ApplicationContext applicationContext;

    @Value("${ops.agent.chat.planner-client-id:}")
    private String plannerClientId;

    @Value("${ops.agent.chat.reviewer-client-id:}")
    private String reviewerClientId;

    @Value("${ops.agent.chat.report-writer-client-id:}")
    private String reportWriterClientId;

    @Value("${ops.agent.chat.client-ready-timeout-ms:0}")
    private long clientReadyTimeoutMs;

    @Value("${ops.agent.chat.use-configured-client:false}")
    private boolean useConfiguredClient;

    @Override
    public OpsChatClientResolution resolve(OpsChatAgentRole role) {
        if (role == null) {
            return OpsChatClientResolution.unavailable(null, "Agent role is required.");
        }

        String configuredClientId = configuredClientId(role);
        if (useConfiguredClient && !isBlank(configuredClientId)) {
            String beanName = AiAgentEnumVO.AI_CLIENT.getBeanName(configuredClientId);
            OpsChatClientResolution resolution = resolveConfiguredBean(role, beanName);
            if (resolution.isAvailable()) {
                return resolution;
            }
            log.warn("Configured ops chat agent bean is unavailable. role={}, beanName={}, reason={}",
                    role, beanName, resolution.getMessage());
            return resolution;
        }

        return resolveFallbackChatModel(role);
    }

    private OpsChatClientResolution resolveConfiguredBean(OpsChatAgentRole role, String beanName) {
        try {
            if (!waitForConfiguredBean(beanName)) {
                return OpsChatClientResolution.unavailable(role, "Configured ChatClient bean does not exist: " + beanName);
            }
            ChatClient chatClient = applicationContext.getBean(beanName, ChatClient.class);
            return OpsChatClientResolution.resolved(role, chatClient, beanName, "AI_CLIENT_BEAN", false,
                    "Resolved dynamic ai_client bean.");
        } catch (Exception e) {
            return OpsChatClientResolution.unavailable(role, "Failed to resolve configured ChatClient bean: " + e.getMessage());
        }
    }

    private boolean waitForConfiguredBean(String beanName) {
        if (applicationContext.containsBean(beanName)) {
            return true;
        }
        if (clientReadyTimeoutMs <= 0) {
            return false;
        }
        long deadline = System.currentTimeMillis() + clientReadyTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return applicationContext.containsBean(beanName);
            }
            if (applicationContext.containsBean(beanName)) {
                return true;
            }
        }
        return applicationContext.containsBean(beanName);
    }

    private OpsChatClientResolution resolveFallbackChatModel(OpsChatAgentRole role) {
        try {
            if (!applicationContext.containsBean("openAiChatModel")) {
                return OpsChatClientResolution.unavailable(role,
                        "No role-specific ai_client bean configured and openAiChatModel bean is unavailable.");
            }
            ChatModel chatModel = applicationContext.getBean("openAiChatModel", ChatModel.class);
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(OpsChatAgentPrompts.systemPrompt(role))
                    .build();
            return OpsChatClientResolution.resolved(role, chatClient, "openAiChatModel", "OPEN_AI_CHAT_MODEL_FALLBACK", true,
                    "Built role-scoped ChatClient from openAiChatModel without dynamic advisors or MCP tool callbacks.");
        } catch (Exception e) {
            return OpsChatClientResolution.unavailable(role, "Failed to build fallback ChatClient: " + e.getMessage());
        }
    }

    private String configuredClientId(OpsChatAgentRole role) {
        return switch (role) {
            case PLANNER -> plannerClientId;
            case EVIDENCE_REVIEWER -> reviewerClientId;
            case REPORT_WRITER -> reportWriterClientId;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

