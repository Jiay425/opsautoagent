package com.opsautoagent.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsIncidentDiagnosis {

    private Long id;

    private String diagnosisId;

    private String sessionId;

    private String serviceName;

    private String startTime;

    private String endTime;

    private String problem;

    private String traceId;

    private String status;

    private String requestJson;

    private String metricEvidenceJson;

    private String logEvidenceJson;

    private String traceEvidenceJson;

    private String evidenceChainJson;

    private String runbookJson;

    private String report;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}

