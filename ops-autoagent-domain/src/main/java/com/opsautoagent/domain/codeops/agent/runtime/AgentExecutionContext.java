package com.opsautoagent.domain.codeops.agent.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentExecutionContext {

    private String executionId;

    private String taskId;

    private String taskType;

    private String traceId;

    private Integer stepNo;

    private String selectedSkill;

    private String decision;

    private String decisionReason;

    private String goal;

    private String repository;

    private String changeRef;

    private AgentBudget budget;

    private List<String> focusAreas;

    private List<String> allowedTools;

    private Map<String, Object> toolConstraints;

    private Map<String, Object> workingMemorySummary;

    private LocalDateTime startTime;

}
