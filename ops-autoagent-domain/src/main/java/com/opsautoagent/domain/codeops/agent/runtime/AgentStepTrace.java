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
public class AgentStepTrace {

    private String executionId;

    private String taskId;

    private String taskType;

    private String traceId;

    private Integer stepNo;

    private String agentOrSkill;

    private String decision;

    private String reason;

    private String status;

    private String summary;

    private Long costMillis;

    private AgentBudget budget;

    private List<String> evidence;

    private List<String> nextActions;

    private Map<String, Object> toolConstraints;

    private Map<String, Object> outputHighlights;

    private String errorType;

    private String errorMessage;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

}
