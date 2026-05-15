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
public class OpsAgentReview {

    private Long id;

    private String reviewId;

    private String diagnosisId;

    private String stateId;

    private String planId;

    private Integer round;

    private String reviewStatus;

    private Boolean sufficient;

    private Integer confidence;

    private String confirmedFactsJson;

    private String weakEvidenceJson;

    private String missingEvidenceJson;

    private String nextActionsJson;

    private String reportConstraintsJson;

    private String stopReason;

    private String reviewerType;

    private String reviewJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}

