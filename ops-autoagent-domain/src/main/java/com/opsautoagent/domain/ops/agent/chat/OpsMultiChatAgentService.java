package com.opsautoagent.domain.ops.agent.chat;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class OpsMultiChatAgentService {

    @Resource
    private OpsChatAgentAdapter opsChatAgentAdapter;

    public OpsChatAgentOutput execute(OpsChatAgentInput input) {
        return opsChatAgentAdapter.call(input);
    }

    public OpsChatAgentOutput plan(OpsChatAgentInput input) {
        return execute(withRole(input, OpsChatAgentRole.PLANNER));
    }

    public OpsChatAgentOutput reviewEvidence(OpsChatAgentInput input) {
        return execute(withRole(input, OpsChatAgentRole.EVIDENCE_REVIEWER));
    }

    public OpsChatAgentOutput writeReport(OpsChatAgentInput input) {
        return execute(withRole(input, OpsChatAgentRole.REPORT_WRITER));
    }

    public OpsChatAgentDefinition definition(OpsChatAgentRole role) {
        return OpsChatAgentPrompts.definition(role);
    }

    private OpsChatAgentInput withRole(OpsChatAgentInput input, OpsChatAgentRole role) {
        if (input == null) {
            return OpsChatAgentInput.builder().role(role).build();
        }
        return input.toBuilder().role(role).build();
    }

}

