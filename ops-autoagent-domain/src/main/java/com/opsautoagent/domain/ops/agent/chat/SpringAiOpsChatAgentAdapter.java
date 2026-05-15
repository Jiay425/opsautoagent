package com.opsautoagent.domain.ops.agent.chat;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class SpringAiOpsChatAgentAdapter implements OpsChatAgentAdapter {

    @Resource
    private OpsChatClientResolver chatClientResolver;

    @Override
    public OpsChatAgentOutput call(OpsChatAgentInput input) {
        OpsChatAgentRole role = input == null ? null : input.getRole();
        if (role == null) {
            return OpsChatAgentOutput.unavailable(null, "Agent role is required.");
        }

        OpsChatAgentInput normalizedInput = normalize(input);
        OpsChatClientResolution resolution = chatClientResolver.resolve(role);
        if (!resolution.isAvailable() || resolution.getChatClient() == null) {
            return OpsChatAgentOutput.unavailable(role, resolution.getMessage());
        }

        String prompt = OpsChatAgentPrompts.buildPrompt(normalizedInput);
        long start = System.currentTimeMillis();
        try {
            String content = resolution.getChatClient().prompt(prompt).call().content();
            long costMillis = System.currentTimeMillis() - start;
            return OpsChatAgentOutput.builder()
                    .role(role)
                    .success(!isBlank(content))
                    .fallback(resolution.isFallback())
                    .content(blankToEmpty(content))
                    .rawContent(blankToEmpty(content))
                    .clientBeanName(resolution.getBeanName())
                    .resolutionSource(resolution.getSource())
                    .costMillis(costMillis)
                    .createTime(LocalDateTime.now())
                    .errorMessage(isBlank(content) ? "ChatClient returned blank content." : null)
                    .build();
        } catch (Exception e) {
            long costMillis = System.currentTimeMillis() - start;
            log.warn("Ops chat agent call failed. role={}, requestId={}, beanName={}",
                    role, normalizedInput.getRequestId(), resolution.getBeanName(), e);
            return OpsChatAgentOutput.builder()
                    .role(role)
                    .success(false)
                    .fallback(true)
                    .content("")
                    .rawContent("")
                    .clientBeanName(resolution.getBeanName())
                    .resolutionSource(resolution.getSource())
                    .costMillis(costMillis)
                    .createTime(LocalDateTime.now())
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private OpsChatAgentInput normalize(OpsChatAgentInput input) {
        if (!isBlank(input.getRequestId())) {
            return input;
        }
        return input.toBuilder()
                .requestId("ops-chat-agent-" + UUID.randomUUID())
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

}

