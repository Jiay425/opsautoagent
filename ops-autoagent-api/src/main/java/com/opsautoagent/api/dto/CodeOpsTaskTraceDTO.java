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
public class CodeOpsTaskTraceDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private String taskType;

    private String goal;

    private String status;

    private String finalSummary;

    private Integer stepCount;

    private Integer usedToolCalls;

    private Map<String, Object> workingMemorySummary;

    private List<CodeOpsTaskTraceStepDTO> timeline;

}
