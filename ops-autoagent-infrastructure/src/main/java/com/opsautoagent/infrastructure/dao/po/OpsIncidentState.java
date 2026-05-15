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
public class OpsIncidentState {

    private Long id;

    private String stateId;

    private String diagnosisId;

    private String sessionId;

    private String eventId;

    private String serviceName;

    private String severity;

    private String alertRule;

    private String timeWindowJson;

    private Integer currentRound;

    private Integer maxRounds;

    private String planJson;

    private String metricsEvidenceJson;

    private String logEvidenceJson;

    private String traceEvidenceJson;

    private String runbookEvidenceJson;

    private String candidateRootCausesJson;

    private String missingEvidenceJson;

    private String toolHistoryJson;

    private String reviewStatus;

    private String finalReport;

    private String status;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}

