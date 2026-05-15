package com.opsautoagent.domain.ops.agent.chat;

import java.util.Arrays;

public enum OpsChatAgentRole {

    PLANNER("planner-agent", "Planner Agent"),
    EVIDENCE_REVIEWER("evidence-reviewer-agent", "Evidence Reviewer Agent"),
    REPORT_WRITER("report-writer-agent", "Report Writer Agent");

    private final String code;
    private final String description;

    OpsChatAgentRole(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static OpsChatAgentRole of(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(role -> role.code.equalsIgnoreCase(code)
                        || role.name().equalsIgnoreCase(code)
                        || role.description.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }

}

