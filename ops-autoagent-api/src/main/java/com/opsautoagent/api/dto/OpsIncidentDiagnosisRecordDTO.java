package com.opsautoagent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsIncidentDiagnosisRecordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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

    private String createTime;

    private String updateTime;

}

