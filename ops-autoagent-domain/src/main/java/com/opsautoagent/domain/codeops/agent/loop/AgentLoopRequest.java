package com.opsautoagent.domain.codeops.agent.loop;

import com.opsautoagent.domain.codeops.agent.runtime.AgentExecutionContext;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentLoopRequest {

    private String goal;

    private EngineeringTaskEntity task;

    private AgentExecutionContext executionContext;

    @Builder.Default
    private List<Map<String, Object>> messages = List.of();

    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Builder.Default
    private int maxTurns = 8;

}
