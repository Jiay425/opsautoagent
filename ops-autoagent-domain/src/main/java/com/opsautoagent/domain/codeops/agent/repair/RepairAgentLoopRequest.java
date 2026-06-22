package com.opsautoagent.domain.codeops.agent.repair;

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
public class RepairAgentLoopRequest {

    private String goal;

    private String repository;

    private EngineeringTaskEntity task;

    @Builder.Default
    private Map<String, Object> inputFailureDiagnostic = new LinkedHashMap<>();

    @Builder.Default
    private int maxTurns = 8;
}
