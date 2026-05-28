package com.opsautoagent.domain.codeops.agent.testpatch;

import com.opsautoagent.domain.codeops.model.entity.CodeSnippetEntity;
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
public class CodeOpsTestPatchAgentInput {

    private String taskId;

    private String taskType;

    private String goal;

    private String repositoryPath;

    private List<String> relatedTestFiles;

    private Map<String, Object> codeLocalization;

    private Map<String, Object> patchGeneration;

    private List<CodeSnippetEntity> testSnippets;

}
