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
public class OpsHistoricalIncidentMemory {

    private Long id;

    private String memoryId;

    private String diagnosisId;

    private String serviceName;

    private String alertRule;

    private String severity;

    private String symptomSummary;

    private String evidenceSummary;

    private String rootCauseCategory;

    private String rootCauseSummary;

    private String remediationSummary;

    private Integer confidence;

    private String reviewStatus;

    private String timeWindowJson;

    private String tags;

    private String similarityText;

    private String sourceRecordJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}

