package com.opsautoagent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsIncidentFixViewDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private String status;

    private String currentStage;

    private Integer progressPercent;

    private Boolean requiresApproval;

    private String approvalStatus;

    private String goal;

    private String repository;

    private String finalSummary;

    private Map<String, Object> incident;

    private Map<String, Object> guardrails;

    private Map<String, Object> approval;

    private Map<String, Object> artifacts;

    private List<CodeOpsIncidentFixStageDTO> stages;

    private CodeOpsTaskTraceDTO trace;

}
