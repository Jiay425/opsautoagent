package com.opsautoagent.domain.codeops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsEvalRun {

    private String runId;

    private String caseId;

    private String taskId;

    private String taskType;

    private String status;

    private BigDecimal expectedSkillCoverage;

    private BigDecimal evidenceKeywordCoverage;

    private BigDecimal artifactCoverage;

    private Integer stepCount;

    private Integer usedToolCalls;

    private Long latencyMs;

    private List<String> missingSkills;

    private List<String> missingEvidenceKeywords;

    private List<String> missingArtifacts;

    private Map<String, Object> detail;

    private String errorMessage;

}
