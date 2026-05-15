package com.opsautoagent.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsEvalMetric {

    private Long id;
    private String runId;
    private String caseId;
    private String metricName;
    private BigDecimal metricValue;
    private String metricDetailJson;
    private LocalDateTime createTime;

}

