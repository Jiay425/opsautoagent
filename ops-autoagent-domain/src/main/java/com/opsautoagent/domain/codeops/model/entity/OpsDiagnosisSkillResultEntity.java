package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsDiagnosisSkillResultEntity {

    private String diagnosisId;

    private String sessionId;

    private String serviceName;

    private String timeWindow;

    private String traceId;

    private String status;

    private String reportSummary;

    private List<String> codeHints;

    private List<String> evidenceSources;

    private Map<String, Object> evidenceDetails;

    private String errorMessage;

}
