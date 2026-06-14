package com.opsautoagent.domain.codeops.agent.skill;

import com.opsautoagent.domain.codeops.agent.evidence.EvidenceGraphBuilderService;
import com.opsautoagent.domain.codeops.agent.localization.CodeLocalizationAgentInput;
import com.opsautoagent.domain.codeops.agent.localization.CodeLocalizationAgentOutput;
import com.opsautoagent.domain.codeops.agent.localization.CodeLocalizationAgentService;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolGateway;
import com.opsautoagent.domain.codeops.model.entity.CodeSnippetEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EvidenceGraphEntity;
import com.opsautoagent.domain.codeops.model.entity.OpsDiagnosisSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffContextEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

@Component
public class RepoUnderstandingSkill implements EngineeringSkill {

    public static final String SKILL_ID = "repo_understanding";

    private final EngineeringToolGateway toolGateway;

    private final CodeLocalizationAgentService codeLocalizationAgentService;

    private final EvidenceGraphBuilderService evidenceGraphBuilderService;

    public RepoUnderstandingSkill(EngineeringToolGateway toolGateway,
                                  CodeLocalizationAgentService codeLocalizationAgentService,
                                  EvidenceGraphBuilderService evidenceGraphBuilderService) {
        this.toolGateway = toolGateway;
        this.codeLocalizationAgentService = codeLocalizationAgentService;
        this.evidenceGraphBuilderService = evidenceGraphBuilderService;
    }

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("Repo Understanding Skill")
                .description("Build repository, diff and code context for one engineering task.")
                .supportedTaskTypes(List.of("CODE_REVIEW", "ISSUE_TO_PATCH", "INCIDENT_TO_FIX", "RELEASE_RISK"))
                .requiredTools(List.of("repo.search_text", "repo.list_files", "repo.git_diff", "repo.find_tests"))
                .riskLevel("READ_ONLY")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        RepoDiffContextEntity diffContext = toolGateway.loadDiffContext(task.getRepository(), task.getChangeRef(), task.getContext());
        String changeRef = value(diffContext.getChangeRef(), "working_tree");
        List<String> codeHints = extractCodeHints(task);
        if (codeHints.isEmpty()) {
            codeHints = extractKeywordsFromGoal(task.getGoal());
        }
        List<String> codeSearchMatches = toolGateway.searchCode(task.getRepository(), codeHints, 30);
        if (codeSearchMatches.isEmpty() && !codeHints.isEmpty()) {
            codeSearchMatches = toolGateway.searchCode(task.getRepository(),
                    codeHints.stream().map(h -> h.replace("Exception", "")
                            .replace("Service", "").replace("Controller", "")).toList(), 20);
        }
        List<CodeSnippetEntity> codeSnippets = loadSnippets(task, codeSearchMatches);
        Map<String, Object> opsDiagnosis = extractOpsDiagnosis(task);
        EvidenceGraphEntity evidenceGraph = evidenceGraphBuilderService.build(
                task, opsDiagnosis, codeHints, codeSearchMatches, codeSnippets);
        CodeLocalizationAgentOutput localization = codeLocalizationAgentService.localize(CodeLocalizationAgentInput.builder()
                .taskId(task.getTaskId())
                .taskType(task.getTaskType())
                .goal(task.getGoal())
                .repositoryPath(value(diffContext.getRepositoryPath(), ""))
                .changeRef(changeRef)
                .opsDiagnosis(opsDiagnosis)
                .codeHints(codeHints)
                .codeSearchMatches(codeSearchMatches)
                .codeSnippets(codeSnippets)
                .evidenceGraph(evidenceGraph)
                .changedFiles(diffContext.getChangedFiles() == null ? List.of() : diffContext.getChangedFiles())
                .relatedTestFiles(diffContext.getRelatedTestFiles() == null ? List.of() : diffContext.getRelatedTestFiles())
                .build());
        Map<String, Object> rawOutput = buildRawOutput(diffContext, changeRef, codeHints,
                codeSearchMatches, codeSnippets, evidenceGraph, localization);
        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status(Boolean.TRUE.equals(diffContext.getDiffAvailable()) ? "SUCCESS" : "NO_DIFF")
                .summary("已完成代码定位上下文构建："
                        + diffContext.getDiffSummary()
                        + "，changeRef=" + changeRef
                        + "，localizationConfidence=" + value(localization.getConfidence(), "LOW"))
                .evidence(List.of(
                        "目标仓库：" + diffContext.getRepositoryPath(),
                        "变更引用：" + changeRef,
                        "关注点：" + (task.getFocusAreas() == null || task.getFocusAreas().isEmpty() ? "未指定" : String.join(",", task.getFocusAreas())),
                        "变更文件：" + list(diffContext.getChangedFiles()),
                        "相关测试：" + list(diffContext.getRelatedTestFiles()),
                        "Diff hunk 数：" + (diffContext.getHunks() == null ? 0 : diffContext.getHunks().size()),
                        "运维代码线索：" + list(codeHints),
                        "证据图摘要：" + value(evidenceGraph.getSummary(), ""),
                        "代码搜索命中：" + list(codeSearchMatches),
                        "LLM 代码定位目标文件：" + list(localization.getTargetFiles()),
                        "LLM 代码定位目标方法：" + list(localization.getTargetMethods())
                ))
                .nextActions(List.of("将代码定位结果传给 Root Cause / Patch Agent", "补充 AST/调用链分析", "根据目标文件定位相关测试"))
                .rawOutput(rawOutput)
                .build();
    }

    private Map<String, Object> buildRawOutput(RepoDiffContextEntity diffContext,
                                               String changeRef,
                                               List<String> codeHints,
                                               List<String> codeSearchMatches,
                                               List<CodeSnippetEntity> codeSnippets,
                                               EvidenceGraphEntity evidenceGraph,
                                               CodeLocalizationAgentOutput localization) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("phase", "PHASE_2_CODE_LOCALIZATION");
        output.put("repositoryPath", value(diffContext.getRepositoryPath(), ""));
        output.put("changeRef", changeRef);
        output.put("changedFiles", diffContext.getChangedFiles() == null ? List.of() : diffContext.getChangedFiles());
        output.put("relatedTestFiles", diffContext.getRelatedTestFiles() == null ? List.of() : diffContext.getRelatedTestFiles());
        output.put("hunkCount", diffContext.getHunks() == null ? 0 : diffContext.getHunks().size());
        output.put("codeHints", codeHints);
        output.put("codeSearchMatches", codeSearchMatches);
        output.put("codeSnippets", codeSnippets);
        output.put("evidenceGraph", evidenceGraph);
        output.put("evidenceGraphSummary", evidenceGraph == null ? "" : evidenceGraph.getSummary());
        output.put("evidenceGraphRankedCodeNodes", evidenceGraph == null ? List.of() : evidenceGraph.getRankedCodeNodes());
        output.put("diffSummary", value(diffContext.getDiffSummary(), ""));
        output.put("diffAvailable", Boolean.TRUE.equals(diffContext.getDiffAvailable()));
        output.put("localizationSuccess", localization.isSuccess());
        output.put("localizationFallback", localization.isFallback());
        output.put("localizationConfidence", value(localization.getConfidence(), "LOW"));
        output.put("strategyType", value(localization.getStrategyType(), "NEED_MORE_EVIDENCE"));
        output.put("shouldEnterCodeRepair", localization.isShouldEnterCodeRepair());
        output.put("targetFiles", localization.getTargetFiles() == null ? List.of() : localization.getTargetFiles());
        output.put("targetMethods", localization.getTargetMethods() == null ? List.of() : localization.getTargetMethods());
        output.put("primarySuspectMethod", value(localization.getPrimarySuspectMethod(), ""));
        output.put("candidateFiles", localization.getCandidateFiles() == null ? List.of() : localization.getCandidateFiles());
        output.put("candidateMethods", localization.getCandidateMethods() == null ? List.of() : localization.getCandidateMethods());
        output.put("scopeSuggestion", value(localization.getScopeSuggestion(), ""));
        output.put("scopeConfidence", value(localization.getScopeConfidence(), value(localization.getConfidence(), "LOW")));
        output.put("expandable", localization.isExpandable());
        output.put("expansionBoundary", localization.getExpansionBoundary() == null ? List.of() : localization.getExpansionBoundary());
        output.put("suspiciousLocations", localization.getSuspiciousLocations() == null ? List.of() : localization.getSuspiciousLocations());
        output.put("localizationReasoning", localization.getReasoning() == null ? List.of() : localization.getReasoning());
        output.put("missingEvidence", localization.getMissingEvidence() == null ? List.of() : localization.getMissingEvidence());
        output.put("localizationError", value(localization.getErrorMessage(), ""));
        return output;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCodeHints(EngineeringTaskEntity task) {
        List<String> hints = new ArrayList<>();
        if (task.getContext() == null) {
            return hints;
        }
        Object directHints = task.getContext().get("codeHints");
        if (directHints instanceof List<?> values) {
            values.stream()
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .forEach(hints::add);
        }
        Object endpoint = task.getContext().get("endpoint");
        if (endpoint != null && !String.valueOf(endpoint).isBlank()) {
            hints.add(String.valueOf(endpoint));
        }
        Object skillOutputs = task.getContext().get("skillOutputs");
        if (!(skillOutputs instanceof Map<?, ?> outputs)) {
            return hints;
        }
        Object opsOutput = outputs.get("ops_diagnosis");
        if (!(opsOutput instanceof Map<?, ?> opsMap)) {
            return hints;
        }
        Object opsDiagnosis = opsMap.get("opsDiagnosis");
        if (opsDiagnosis instanceof OpsDiagnosisSkillResultEntity diagnosis) {
            if (diagnosis.getCodeHints() != null) {
                diagnosis.getCodeHints().stream()
                        .filter(value -> value != null && !value.isBlank())
                        .forEach(hints::add);
            }
            return hints.stream().distinct().limit(30).toList();
        }
        if (!(opsDiagnosis instanceof Map<?, ?> diagnosisMap)) {
            return hints;
        }
        Object codeHints = diagnosisMap.get("codeHints");
        if (codeHints instanceof List<?> values) {
            values.stream()
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .forEach(hints::add);
        }
        return hints.stream().distinct().limit(30).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractOpsDiagnosis(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return Map.of();
        }
        Object skillOutputs = task.getContext().get("skillOutputs");
        if (!(skillOutputs instanceof Map<?, ?> outputs)) {
            return Map.of();
        }
        Object opsOutput = outputs.get("ops_diagnosis");
        if (opsOutput instanceof Map<?, ?> opsMap) {
            Map<String, Object> value = new LinkedHashMap<>();
            opsMap.forEach((key, item) -> value.put(String.valueOf(key), item));
            return value;
        }
        return Map.of();
    }

    private List<CodeSnippetEntity> loadSnippets(EngineeringTaskEntity task, List<String> codeSearchMatches) {
        if (codeSearchMatches == null || codeSearchMatches.isEmpty()) {
            return List.of();
        }
        List<CodeSnippetEntity> snippets = new ArrayList<>();
        for (String match : codeSearchMatches) {
            MatchLocation location = parseMatchLocation(match);
            if (location == null) {
                continue;
            }
            snippets.add(toolGateway.readFileSnippet(task.getRepository(), location.filePath(), location.lineNumber(), 8));
            if (snippets.size() >= 8) {
                break;
            }
        }
        return snippets;
    }

    private MatchLocation parseMatchLocation(String match) {
        if (match == null || match.isBlank()) {
            return null;
        }
        int firstColon = match.indexOf(':');
        if (firstColon <= 0) {
            return null;
        }
        int secondColon = match.indexOf(':', firstColon + 1);
        if (secondColon <= firstColon) {
            return null;
        }
        String filePath = match.substring(0, firstColon);
        try {
            int lineNumber = Integer.parseInt(match.substring(firstColon + 1, secondColon).trim());
            return new MatchLocation(filePath, lineNumber);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String value(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private String list(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "无";
        }
        return String.join(", ", values);
    }

    private List<String> extractKeywordsFromGoal(String goal) {
        if (goal == null || goal.isBlank()) return List.of();
        List<String> keywords = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([A-Z][a-zA-Z0-9_]*(?:Service|Controller|Repository|Exception|Error))")
                .matcher(goal);
        while (m.find()) keywords.add(m.group(1));
        return keywords;
    }

    private record MatchLocation(String filePath, int lineNumber) {
    }

}
