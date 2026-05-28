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
public class BugFixSuggestionEntity {

    private String repositoryPath;

    private String changeRef;

    private List<String> changedFiles;

    private List<String> suspiciousLocations;

    private List<String> diagnosisClues;

    private List<String> fixSuggestions;

    private String patchDraft;

    private String rootCause;

    private String confidence;

    private Boolean llmGenerated;

    private String llmErrorMessage;

    private List<CodeSnippetEntity> codeSnippets;

    private List<String> verificationHints;

}
