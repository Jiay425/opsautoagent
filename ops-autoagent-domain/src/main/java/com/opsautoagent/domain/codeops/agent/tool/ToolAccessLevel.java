package com.opsautoagent.domain.codeops.agent.tool;

public enum ToolAccessLevel {

    READ_ONLY,
    LOW_RISK_WRITE,
    SOURCE_WRITE,
    COMMAND_EXECUTE,
    EXTERNAL_CALL,
    HIGH_RISK_WRITE

}
