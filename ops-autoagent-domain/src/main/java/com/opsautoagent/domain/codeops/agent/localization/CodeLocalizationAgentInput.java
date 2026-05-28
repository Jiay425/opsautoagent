package com.opsautoagent.domain.codeops.agent.localization;

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
public class CodeLocalizationAgentInput {

    private String taskId;

    private String taskType;

    private String goal;

    private String repositoryPath;

    private String changeRef;

    private Map<String, Object> opsDiagnosis;

    private List<String> codeHints;

    private List<String> codeSearchMatches;

    private List<CodeSnippetEntity> codeSnippets;

    private List<String> changedFiles;

    private List<String> relatedTestFiles;

}
