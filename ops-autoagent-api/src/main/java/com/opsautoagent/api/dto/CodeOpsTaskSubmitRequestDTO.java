package com.opsautoagent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Request body for one CodeOps engineering task.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsTaskSubmitRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Engineering task type, for example CODE_REVIEW, INCIDENT_TO_FIX or ISSUE_TO_PATCH.
     */
    @NotBlank(message = "taskType cannot be blank")
    private String taskType;

    /**
     * Natural language engineering goal.
     */
    @NotBlank(message = "goal cannot be blank")
    @Size(max = 4000, message = "goal length must be <= 4000")
    private String goal;

    /**
     * Optional repository path or logical repository name.
     */
    private String repository;

    /**
     * Optional branch, commit, PR or diff reference.
     */
    private String changeRef;

    /**
     * Optional focus areas such as transaction, cache, performance or tests.
     */
    private List<String> focusAreas;

    /**
     * Optional task context from frontend or future integrations.
     */
    private Map<String, Object> context;

    @Min(value = 1, message = "maxRounds must be >= 1")
    @Max(value = 12, message = "maxRounds must be <= 12")
    private Integer maxRounds;

    @Min(value = 1, message = "maxToolCalls must be >= 1")
    @Max(value = 50, message = "maxToolCalls must be <= 50")
    private Integer maxToolCalls;

}
