package com.opsautoagent.domain.codeops.agent.loop;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentLoopToolCall {

    @Builder.Default
    private String toolCallId = UUID.randomUUID().toString();

    private String toolName;

    @Builder.Default
    private Map<String, Object> arguments = new LinkedHashMap<>();

}
