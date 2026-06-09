package com.opsautoagent.domain.codeops.agent.tool;

@FunctionalInterface
public interface EngineeringToolHandler {

    EngineeringToolResult execute(EngineeringToolRequest request);

}
