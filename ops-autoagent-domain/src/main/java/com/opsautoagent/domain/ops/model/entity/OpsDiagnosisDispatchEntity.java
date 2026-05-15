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
public class OpsDiagnosisDispatchEntity {

    private String dispatchId;

    private String eventId;

    private String diagnosisId;

    private String serviceName;

    private String dedupKey;

    private String dispatchStatus;

    private String skipReason;

    private LocalDateTime createTime;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime updateTime;

}

