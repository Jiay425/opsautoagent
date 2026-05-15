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
public class OpsAuditLogEntity {

    private String auditId;

    private String sessionId;

    private String diagnosisId;

    private String operatorId;

    private String clientIp;

    private String action;

    private String resource;

    private String requestJson;

    private String result;

    private String reason;

    private LocalDateTime createTime;

}

