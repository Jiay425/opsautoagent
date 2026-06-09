package com.opsautoagent.domain.codeops.agent.tool;

import com.opsautoagent.domain.codeops.agent.runtime.AgentExecutionContext;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EngineeringToolRequest {

    private String toolName;

    @Builder.Default
    private Map<String, Object> arguments = new LinkedHashMap<>();

    private EngineeringTaskEntity task;

    private AgentExecutionContext executionContext;

    public Object argument(String name) {
        return arguments == null ? null : arguments.get(name);
    }

    public String stringArgument(String name) {
        Object value = argument(name);
        return value == null ? "" : String.valueOf(value);
    }

    public int intArgument(String name, int defaultValue) {
        Object value = argument(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long longArgument(String name, long defaultValue) {
        Object value = argument(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

}
