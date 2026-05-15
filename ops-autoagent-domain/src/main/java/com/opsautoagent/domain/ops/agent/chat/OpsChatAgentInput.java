package com.opsautoagent.domain.ops.agent.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OpsChatAgentInput {

    private OpsChatAgentRole role;

    private String requestId;

    private String diagnosisId;

    private String sessionId;

    private String serviceName;

    private String objective;

    private String incidentContext;

    private String incidentStateJson;

    private String workingMemoryJson;

    private String historicalMemoryJson;

    private String toolConstraintsJson;

    private String planJson;

    private String evidenceJson;

    private String reviewerResultJson;

    private String runbookJson;

    private List<String> constraints;

    private Map<String, Object> metadata;

}

