package com.opsautoagent.domain.codeops.agent.review;

import com.opsautoagent.domain.codeops.model.entity.RepoDiffHunkEntity;
import com.opsautoagent.domain.codeops.model.entity.ReviewFindingEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringKnowledgeMatchEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsReviewAgentInput {

    private String taskId;

    private String taskType;

    private String goal;

    private String repositoryPath;

    private String changeRef;

    private List<String> focusAreas;

    private List<String> changedFiles;

    private List<String> relatedTestFiles;

    private List<RepoDiffHunkEntity> hunks;

    private List<ReviewFindingEntity> baselineFindings;

    private List<EngineeringKnowledgeMatchEntity> knowledgeMatches;

}
