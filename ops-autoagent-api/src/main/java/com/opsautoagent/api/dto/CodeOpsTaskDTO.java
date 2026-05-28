package com.opsautoagent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * CodeOps engineering task response.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsTaskDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private String taskType;

    private String goal;

    private String repository;

    private String changeRef;

    private String status;

    private Integer maxRounds;

    private Integer maxToolCalls;

    private String finalSummary;

    private List<CodeOpsTaskStepDTO> steps;

    private String createTime;

    private String updateTime;

}
