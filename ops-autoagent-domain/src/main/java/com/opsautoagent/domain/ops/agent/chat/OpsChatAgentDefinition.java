package com.opsautoagent.domain.ops.agent.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsChatAgentDefinition {

    private OpsChatAgentRole role;

    private String agentName;

    private String systemPrompt;

    private String outputSchema;

    private List<String> requiredInputs;

    private List<String> allowedTools;

}

