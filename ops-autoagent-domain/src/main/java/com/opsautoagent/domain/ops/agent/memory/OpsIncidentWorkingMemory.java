package com.opsautoagent.domain.ops.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OpsIncidentWorkingMemory {

    private String memoryId;

    private String diagnosisId;

    private String sessionId;

    private String serviceName;

    private String alertName;

    private String severity;

    private String alertRule;

    private String startTime;

    private String endTime;

    private String currentStatus;

    private String planId;

    private Integer planRound;

    private String plannerType;

    private String alertType;

    private String planJson;

    private String hypothesesJson;

    private String requiredToolsJson;

    private String expectedEvidenceJson;

    private String toolHistoryJson;

    private String metricEvidenceSummary;

    private String logEvidenceSummary;

    private String traceEvidenceSummary;

    private String evidenceSignalsJson;

    private String evidenceSemanticsJson;

    private String runbookMatchesJson;

    private String reviewerStatus;

    private String reviewerResultJson;

    private String missingEvidenceJson;

    private String rootCauseCandidatesJson;

    private String finalReportSummary;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}

