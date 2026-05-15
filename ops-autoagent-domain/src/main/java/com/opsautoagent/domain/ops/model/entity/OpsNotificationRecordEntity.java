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
public class OpsNotificationRecordEntity {

    private String notificationId;

    private String diagnosisId;

    private String serviceName;

    private String channel;

    private String receiver;

    private String severity;

    private String subject;

    private String sendStatus;

    private Integer retryCount;

    private String errorMessage;

    private LocalDateTime sendTime;

    private LocalDateTime createTime;

}

