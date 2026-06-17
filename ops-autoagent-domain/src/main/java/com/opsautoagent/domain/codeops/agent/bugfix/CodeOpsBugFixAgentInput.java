package com.opsautoagent.domain.codeops.agent.bugfix;

import com.opsautoagent.domain.codeops.model.entity.CodeSnippetEntity;
import com.opsautoagent.domain.codeops.model.entity.CodeContextPackEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringKnowledgeMatchEntity;
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
public class CodeOpsBugFixAgentInput {

    private String taskId;

    private String taskType;

    private String goal;

    private String repositoryPath;

    private String changeRef;

    private Map<String, Object> opsDiagnosis;

    private List<String> diagnosisClues;

    private List<String> suspiciousLocations;

    private List<String> codeSearchMatches;

    private List<CodeSnippetEntity> codeSnippets;

    private CodeContextPackEntity codeContextPack;

    private List<EngineeringKnowledgeMatchEntity> knowledgeMatches;

    private Map<String, Object> repairScope;

    private Map<String, Object> repairPlan;

    private List<Object> reflectionFailures;

    private List<Object> reflectionDiagnostics;

    private List<Map<String, Object>> memoryHints;

}
