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
public class OpsAlertEventEntity {

    private String eventId;

    private String source;

    private String serviceName;

    private String alertRule;

    private String severity;

    private String status;

    private String fingerprint;

    private String traceId;

    private LocalDateTime startsAt;

    private LocalDateTime endsAt;

    private String labelsJson;

    private String annotationsJson;

    private String rawPayload;

    private LocalDateTime receivedTime;

    private LocalDateTime createTime;

}

