package com.opsautoagent.domain.codeops.agent.eval;

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
public class CodeOpsEvalCase {

    private String caseId;

    private String caseName;

    private String taskType;

    private String goal;

    private String repository;

    private String changeRef;

    private List<String> focusAreas;

    private Map<String, Object> context;

    private List<String> expectedSkills;

    private List<String> expectedEvidenceKeywords;

    private List<String> expectedArtifacts;

    private List<String> expectedTargetFiles;

    private List<String> expectedTargetMethods;

    private String expectedFixStrategy;

    private String expectedScopeDecision;

    private List<String> expectedPatchKeywords;

    private List<String> expectedTestNames;

    private List<String> expectedRiskKeywords;

}
