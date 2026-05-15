package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsToolCallLogEntity {

    private String callId;

    private String sessionId;

    private String diagnosisId;

    private String toolName;

    private String logicalToolName;

    private String protocol;

    private String governanceDecision;

    private String target;

    private String requestSummary;

    private String responseSummary;

    private Integer statusCode;

    private Long costMillis;

    private String success;

    private String errorMessage;

    private LocalDateTime createTime;

}

