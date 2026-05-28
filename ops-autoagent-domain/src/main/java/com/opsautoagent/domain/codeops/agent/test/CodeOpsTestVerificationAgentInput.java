package com.opsautoagent.domain.codeops.agent.test;

import com.opsautoagent.domain.codeops.model.entity.TestVerificationPlanEntity;
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
public class CodeOpsTestVerificationAgentInput {

    private String taskId;

    private String taskType;

    private String goal;

    private String repositoryPath;

    private String changeRef;

    private String diffSummary;

    private List<String> changedFiles;

    private List<String> relatedTestFiles;

    private Map<String, Object> codeLocalization;

    private Map<String, Object> patchGeneration;

    private TestVerificationPlanEntity baselinePlan;

}
