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
public class TestVerificationPlanEntity {

    private String repositoryPath;

    private String changeRef;

    private List<String> changedFiles;

    private List<String> relatedTestFiles;

    private List<String> recommendedTests;

    private List<String> coverageGaps;

    private List<String> mavenCommands;

    private List<String> verificationNotes;

    private List<String> testExecutionResults;

}
