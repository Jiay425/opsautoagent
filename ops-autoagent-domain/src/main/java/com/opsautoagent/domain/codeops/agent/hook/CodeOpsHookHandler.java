package com.opsautoagent.domain.codeops.agent.hook;

import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;

import java.util.Map;

public interface CodeOpsHookHandler {

    String name();

    default boolean supports(CodeOpsHookEvent event, String phase, Map<String, Object> payload) {
        return true;
    }

    CodeOpsHookDecision handle(EngineeringTaskEntity task,
                               CodeOpsHookEvent event,
                               String phase,
                               Map<String, Object> payload);
}
