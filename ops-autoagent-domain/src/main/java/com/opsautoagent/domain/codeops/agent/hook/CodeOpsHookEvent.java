package com.opsautoagent.domain.codeops.agent.hook;

public enum CodeOpsHookEvent {

    BEFORE_TOOL_USE,
    AFTER_TOOL_USE,
    BEFORE_PATCH_APPLY,
    AFTER_PATCH_APPLY,
    AFTER_COMPILE,
    AFTER_TEST,
    ON_FAILURE_DIAGNOSTIC,
    BEFORE_RELEASE_RISK
}
