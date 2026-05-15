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

/**
 * Request body for one-shot intelligent incident diagnosis.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsIncidentAnalyzeRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Service or application name, for example ops-demo-service.
     */
    @NotBlank(message = "serviceName cannot be blank")
    private String serviceName;

    /**
     * Diagnosis window start time. MVP accepts the frontend/business format directly.
     */
    @NotBlank(message = "startTime cannot be blank")
    private String startTime;

    /**
     * Diagnosis window end time. MVP accepts the frontend/business format directly.
     */
    @NotBlank(message = "endTime cannot be blank")
    private String endTime;

    /**
     * User problem description.
     */
    @NotBlank(message = "problem cannot be blank")
    @Size(max = 2000, message = "problem length must be <= 2000")
    private String problem;

    /**
     * Optional trace id. When absent the MVP still analyzes metrics and logs.
     */
    private String traceId;

    /**
     * Max diagnosis steps reserved for future configurable workflow.
     */
    @Min(value = 1, message = "maxStep must be >= 1")
    @Max(value = 10, message = "maxStep must be <= 10")
    private Integer maxStep;

}

