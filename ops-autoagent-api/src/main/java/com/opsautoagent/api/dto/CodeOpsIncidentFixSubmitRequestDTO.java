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
public class CodeOpsIncidentFixSubmitRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String serviceName;

    private String alertRule;

    private String severity;

    private String problem;

    private String endpoint;

    private String traceId;

    private String startTime;

    private String endTime;

    private String repository;

    private String changeRef;

    private Boolean allowPatchApply;

    private Boolean allowTestPatchApply;

    private Boolean fixtureFallbackAllowed;

    private List<String> focusAreas;

    private Map<String, Object> labels;

    private Map<String, Object> annotations;

    private Map<String, Object> context;

    private Integer maxRounds;

    private Integer maxToolCalls;

}
