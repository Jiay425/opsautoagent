package com.opsautoagent.api.dto;

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
public class CodeOpsAgentLoopRunRequestDTO {

    private String goal;

    private String repository;

    private String changeRef;

    private List<String> focusAreas;

    @Builder.Default
    private Map<String, Object> context = new LinkedHashMap<>();

    private Integer maxTurns;

    private Boolean dryRun;

    private Boolean includeSteps;

}
