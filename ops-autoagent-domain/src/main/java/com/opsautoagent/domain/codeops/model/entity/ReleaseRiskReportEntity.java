package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReleaseRiskReportEntity {

    private String repositoryPath;

    private String changeRef;

    private String riskLevel;

    private List<String> impactScopes;

    private List<String> riskPoints;

    private List<String> regressionFocus;

    private List<String> onlineObservationMetrics;

    private List<String> rollbackFocus;

    private List<String> knowledgeReferences;

}
