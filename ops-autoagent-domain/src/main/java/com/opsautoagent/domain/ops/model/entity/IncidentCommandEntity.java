package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IncidentCommandEntity {

    private String serviceName;

    private String startTime;

    private String endTime;

    private String problem;

    private String endpoint;

    private String traceId;

    private Integer maxStep;

    private String sessionId;

    private String diagnosisId;

}

