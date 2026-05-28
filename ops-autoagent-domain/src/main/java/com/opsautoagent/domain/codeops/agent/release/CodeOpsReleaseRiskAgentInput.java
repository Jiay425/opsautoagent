package com.opsautoagent.domain.codeops.agent.release;

import com.opsautoagent.domain.codeops.model.entity.EngineeringKnowledgeMatchEntity;
import com.opsautoagent.domain.codeops.model.entity.ReleaseRiskReportEntity;
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
public class CodeOpsReleaseRiskAgentInput {

    private String taskId;

    private String taskType;

    private String goal;

    private String repositoryPath;

    private String changeRef;

    private String diffSummary;

    private List<String> changedFiles;

    private List<String> relatedTestFiles;

    private Map<String, Object> opsEvidence;

    private Map<String, Object> fixStrategy;

    private Map<String, Object> codeLocalization;

    private Map<String, Object> patchGeneration;

    private Map<String, Object> testVerification;

    private List<Object> reflectionFailures;

    private List<EngineeringKnowledgeMatchEntity> knowledgeMatches;

    private ReleaseRiskReportEntity baselineReport;

}
