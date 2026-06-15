package com.opsautoagent.domain.codeops.agent.skill;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.codeops.agent.llm.CodeOpsAgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.llm.MockCodeOpsAgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopRequest;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopResult;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopStep;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopTraceItem;
import com.opsautoagent.domain.codeops.agent.runtime.AgentExecutionContext;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolGateway;
import com.opsautoagent.domain.codeops.model.entity.CodeContextPackEntity;
import com.opsautoagent.domain.codeops.model.entity.CodeSnippetEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentLoopEngineeringSkill implements EngineeringSkill {

    public static final String SKILL_ID = "agent_loop_investigation";

    private static final Pattern JAVA_PATH_PATTERN = Pattern.compile("([A-Za-z0-9_./\\\\-]+\\.java)");
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9_]{2,})\\b");
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\.([a-z][A-Za-z0-9_]{2,})\\b");
    private static final Pattern LOCATION_PATTERN = Pattern.compile("([^\\s:]+\\.(?:java|xml|yml|yaml|properties)):(\\d+)");

    private final AgentLoopService agentLoopService;
    private final CodeOpsAgentLoopModelClient modelClient;
    private final MockCodeOpsAgentLoopModelClient mockModelClient;
    private final EngineeringToolGateway toolGateway;

    public AgentLoopEngineeringSkill(AgentLoopService agentLoopService,
                                     CodeOpsAgentLoopModelClient modelClient,
                                     MockCodeOpsAgentLoopModelClient mockModelClient,
                                     EngineeringToolGateway toolGateway) {
        this.agentLoopService = agentLoopService;
        this.modelClient = modelClient;
        this.mockModelClient = mockModelClient;
        this.toolGateway = toolGateway;
    }

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("Agent Loop Investigation Skill")
                .description("Run a model-driven read-only tool loop for repository investigation and evidence collection.")
                .supportedTaskTypes(List.of("CODE_REVIEW", "ISSUE_TO_PATCH", "INCIDENT_TO_FIX", "RELEASE_RISK", "AGENT_LOOP_DEBUG"))
                .requiredTools(List.of("repo.create_snapshot", "repo.search_text", "repo.read_file_snippet", "repo.git_diff", "repo.maven"))
                .riskLevel("READ_ONLY")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        Map<String, Object> metadata = task == null || task.getContext() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(task.getContext());
        Map<String, Object> preLoopCodeContextPack = buildPreLoopCodeContextPack(task);
        metadata.put("preLoopCodeContextPack", preLoopCodeContextPack);
        AgentLoopResult result = agentLoopService.run(AgentLoopRequest.builder()
                .goal(buildGoal(task))
                .task(task)
                .executionContext(resolveExecutionContext(task))
                .maxTurns(resolveMaxTurns(task))
                .metadata(metadata)
                .build(), resolveModelClient(task));
        Map<String, Object> rawOutput = buildRawOutput(result);
        rawOutput.put("preLoopCodeContextPack", preLoopCodeContextPack);
        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status(result.isSuccess() ? "SUCCESS" : value(result.getStatus(), "FAILED"))
                .summary(result.isSuccess()
                        ? "Agent loop investigation completed: " + value(result.getFinalAnswer(), "")
                        : "Agent loop investigation stopped: " + value(result.getStopReason(), result.getStatus()))
                .evidence(buildEvidence(result))
                .nextActions(List.of("将 agent loop 调查摘要传递给后续代码定位、修复或风险分析阶段",
                        "必要时打开 includeSteps 或提高 maxTurns 获取更完整的工具调用证据"))
                .rawOutput(rawOutput)
                .build();
    }

    private String buildGoal(EngineeringTaskEntity task) {
        String goal = task == null ? "" : value(task.getGoal(), "");
        if (goal.isBlank()) {
            goal = "Investigate the repository and summarize relevant code and tests.";
        }
        return goal + "\n\nUse read-only tools first. Summarize target files, likely tests, and remaining uncertainty.";
    }

    private AgentExecutionContext resolveExecutionContext(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) {
            return null;
        }
        Object value = task.getContext().get("agentRuntimeContext");
        return value instanceof AgentExecutionContext context ? context : null;
    }

    private AgentLoopModelClient resolveModelClient(EngineeringTaskEntity task) {
        if (task != null && task.getContext() != null
                && Boolean.TRUE.equals(task.getContext().get("agentLoopDryRun"))) {
            return mockModelClient;
        }
        return modelClient;
    }

    private int resolveMaxTurns(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) {
            return 5;
        }
        Object value = task.getContext().get("agentLoopMaxTurns");
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 12));
        }
        try {
            return value == null ? 5 : Math.max(1, Math.min(Integer.parseInt(String.valueOf(value)), 12));
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private Map<String, Object> buildRawOutput(AgentLoopResult result) {
        Map<String, Object> structured = parseStructuredFinalAnswer(result == null ? "" : result.getFinalAnswer());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("phase", "PHASE_AGENT_LOOP_INVESTIGATION");
        output.put("status", result == null ? "FAILED" : result.getStatus());
        output.put("summary", value(stringValue(structured.get("summary")), result == null ? "" : result.getFinalAnswer()));
        output.put("finalAnswer", result == null ? "" : result.getFinalAnswer());
        output.put("structuredFinalAnswer", structured);
        output.put("stopReason", result == null ? "" : result.getStopReason());
        output.put("turns", result == null ? 0 : result.getTurns());
        output.put("trace", result == null ? List.of() : result.getTrace());
        List<String> directEvidenceFiles = listValue(structured.get("directEvidenceFiles"));
        List<String> relatedFiles = listValue(structured.get("relatedFiles"));
        List<String> rootCauseCandidateFiles = listValue(structured.get("rootCauseCandidateFiles"));
        List<String> doNotModifyFiles = listValue(structured.get("doNotModifyFiles"));
        List<String> targetFiles = firstNonEmptyList(listValue(structured.get("targetFiles")), rootCauseCandidateFiles);
        if (targetFiles.isEmpty()) {
            targetFiles = extractJavaPaths(result);
        }
        if (rootCauseCandidateFiles.isEmpty()) {
            rootCauseCandidateFiles = targetFiles;
        }
        output.put("targetFiles", targetFiles);
        output.put("directEvidenceFiles", directEvidenceFiles);
        output.put("relatedFiles", relatedFiles);
        output.put("rootCauseCandidateFiles", rootCauseCandidateFiles);
        output.put("doNotModifyFiles", doNotModifyFiles);
        output.put("candidateFiles", mergeDistinct(rootCauseCandidateFiles, relatedFiles, directEvidenceFiles));
        List<String> targetMethods = mergeDistinct(
                listValue(structured.get("targetMethods")),
                listValue(structured.get("suspectedRootCauseLocations")));
        output.put("targetMethods", targetMethods);
        output.put("candidateMethods", targetMethods);
        String fixStrategy = normalizeFixStrategy(stringValue(structured.get("fixStrategy")),
                booleanValue(structured.get("shouldEnterCodeRepair"), shouldEnterCodeRepair(result, targetFiles)));
        String scopeDecision = normalizeScopeDecision(stringValue(structured.get("scopeDecision")), targetFiles, targetMethods, fixStrategy);
        output.put("fixStrategy", fixStrategy);
        output.put("strategyType", fixStrategy);
        output.put("scopeDecisionType", scopeDecision);
        output.put("rootCauseLocationType", value(stringValue(structured.get("rootCauseLocationType")), "UNKNOWN"));
        output.put("primarySymptomLocation", stringValue(structured.get("primarySymptomLocation")));
        output.put("suspectedRootCauseLocations", listValue(structured.get("suspectedRootCauseLocations")));
        output.put("supportingCodeEvidence", listValue(structured.get("supportingCodeEvidence")));
        output.put("negativeEvidence", listValue(structured.get("negativeEvidence")));
        output.put("reasoning", value(stringValue(structured.get("reasoning")), stringValue(structured.get("summary"))));
        output.put("candidateScope", candidateScope(structured, targetFiles, targetMethods, scopeDecision, fixStrategy));
        output.put("localizationDecision", localizationDecision(output));
        output.put("codeLocalization", localizationDecision(output));
        List<String> recommendedTests = listValue(structured.get("recommendedTests"));
        if (recommendedTests.isEmpty()) {
            recommendedTests = extractTestFiles(result);
        }
        output.put("recommendedTests", recommendedTests);
        output.put("shouldEnterCodeRepair", booleanValue(structured.get("shouldEnterCodeRepair"),
                shouldEnterCodeRepair(result, targetFiles)));
        output.put("localizationConfidence", value(stringValue(structured.get("localizationConfidence")),
                result != null && result.isSuccess() ? "MEDIUM" : "LOW"));
        output.put("missingEvidence", listValue(structured.get("missingEvidence")));
        return output;
    }

    private Map<String, Object> buildPreLoopCodeContextPack(EngineeringTaskEntity task) {
        if (task == null || isBlank(task.getRepository())) {
            return Map.of(
                    "strategy", "PRE_LOOP_CONTEXT_PACK_SKIPPED",
                    "reason", "repository is blank");
        }
        List<String> searchTerms = collectCodeSearchTerms(task);
        List<String> searchMatches = searchTerms.isEmpty()
                ? List.of()
                : toolGateway.searchCode(task.getRepository(), searchTerms, 40);
        List<String> primaryFiles = collectContextFiles(task);
        List<Location> locations = parseLocations(searchMatches);
        List<String> candidateFiles = mergeDistinct(primaryFiles, locations.stream()
                .map(Location::filePath)
                .filter(path -> path.endsWith(".java"))
                .toList());
        List<CodeSnippetEntity> snippets = new ArrayList<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String file : primaryFiles) {
            addPreLoopSnippet(snippets, task.getRepository(), file, 1, 260);
            reasons.put(file, "direct file from task context or previous localization evidence");
        }
        for (Location location : locations) {
            addPreLoopSnippet(snippets, task.getRepository(), location.filePath(), location.line(), 36);
            reasons.putIfAbsent(location.filePath(), "local search hit before Agent Loop: " + abbreviate(location.raw(), 180));
            if (snippets.size() >= 8) {
                break;
            }
        }
        for (String relatedFile : inferSamePackageDependencies(task.getRepository(), snippets)) {
            addPreLoopSnippet(snippets, task.getRepository(), relatedFile, 1, 220);
            reasons.putIfAbsent(relatedFile, "same-package class referenced by direct/candidate source");
            if (snippets.size() >= 11) {
                break;
            }
        }
        List<String> relatedTests = findLikelyTests(task.getRepository(), candidateFiles);
        for (String testFile : relatedTests) {
            addPreLoopSnippet(snippets, task.getRepository(), testFile, 1, 180);
            reasons.putIfAbsent(testFile, "likely regression test for candidate source file");
            if (snippets.size() >= 13) {
                break;
            }
        }
        List<String> buildFiles = addBuildContext(task.getRepository(), snippets, reasons);
        long available = snippets.stream().filter(snippet -> Boolean.TRUE.equals(snippet.getAvailable())).count();
        CodeContextPackEntity pack = CodeContextPackEntity.builder()
                .strategy("PRE_LOOP_EVIDENCE_BACKED_CODE_CONTEXT")
                .primaryFiles(primaryFiles)
                .candidateFiles(candidateFiles.stream().limit(12).toList())
                .supportFiles(inferSupportFiles(snippets, primaryFiles, candidateFiles, relatedTests, buildFiles))
                .relatedTests(relatedTests)
                .buildFiles(buildFiles)
                .contextReasons(reasons)
                .snippets(snippets)
                .availableSnippetCount((int) available)
                .totalSnippetCount(snippets.size())
                .missingFiles(snippets.stream()
                        .filter(snippet -> !Boolean.TRUE.equals(snippet.getAvailable()))
                        .map(CodeSnippetEntity::getFilePath)
                        .filter(path -> !isBlank(path))
                        .distinct()
                        .toList())
                .build();
        return codeContextPackForPrompt(pack, searchTerms, searchMatches);
    }

    private List<String> collectCodeSearchTerms(EngineeringTaskEntity task) {
        Set<String> terms = new LinkedHashSet<>();
        collectSearchTermsFromText(terms, task.getGoal());
        if (task.getFocusAreas() != null) {
            task.getFocusAreas().forEach(value -> collectSearchTermsFromText(terms, value));
        }
        if (task.getContext() != null) {
            for (String key : List.of("endpoint", "serviceName", "codeHints", "suspiciousLocations",
                    "targetFiles", "targetMethods", "directEvidenceFiles", "rootCauseCandidateFiles",
                    "className", "methodName", "exceptionClass", "errorClass", "rawEvidence")) {
                Object value = task.getContext().get(key);
                collectSearchTermsFromObject(terms, value);
            }
        }
        return terms.stream()
                .filter(term -> term.length() >= 3)
                .filter(term -> !isNoisySearchTerm(term))
                .limit(16)
                .toList();
    }

    private void collectSearchTermsFromObject(Set<String> terms, Object value) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectSearchTermsFromObject(terms, item);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectSearchTermsFromObject(terms, item));
            return;
        }
        collectSearchTermsFromText(terms, value == null ? "" : String.valueOf(value));
    }

    private void collectSearchTermsFromText(Set<String> terms, String text) {
        if (isBlank(text)) {
            return;
        }
        Matcher classMatcher = CLASS_NAME_PATTERN.matcher(text);
        while (classMatcher.find()) {
            terms.add(classMatcher.group(1));
        }
        Matcher methodMatcher = METHOD_NAME_PATTERN.matcher(text);
        while (methodMatcher.find()) {
            terms.add(methodMatcher.group(1));
        }
        for (String token : text.split("[^A-Za-z0-9_/-]+")) {
            if (token.startsWith("/api/")) {
                terms.add(lastPathSegment(token));
            }
        }
    }

    private List<String> collectContextFiles(EngineeringTaskEntity task) {
        Set<String> files = new LinkedHashSet<>();
        collectJavaPaths(files, task == null ? "" : task.getGoal());
        if (task != null && task.getContext() != null) {
            for (String key : List.of("targetFiles", "directEvidenceFiles", "rootCauseCandidateFiles",
                    "relatedFiles", "suspiciousLocations", "codeHints")) {
                collectFilesFromObject(files, task.getContext().get(key));
            }
        }
        return files.stream()
                .map(this::normalizeFilePath)
                .filter(path -> path.endsWith(".java"))
                .limit(8)
                .toList();
    }

    private void collectFilesFromObject(Set<String> files, Object value) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectFilesFromObject(files, item);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectFilesFromObject(files, item));
            return;
        }
        collectJavaPaths(files, value == null ? "" : String.valueOf(value));
    }

    private void addPreLoopSnippet(List<CodeSnippetEntity> snippets,
                                   String repository,
                                   String filePath,
                                   int centerLine,
                                   int radius) {
        String normalized = normalizeFilePath(filePath);
        if (isBlank(normalized) || snippets.stream().anyMatch(snippet -> normalized.equals(snippet.getFilePath()))) {
            return;
        }
        snippets.add(toolGateway.readFileSnippet(repository, normalized, centerLine, radius));
    }

    private List<Location> parseLocations(List<String> searchMatches) {
        List<Location> locations = new ArrayList<>();
        if (searchMatches == null) {
            return locations;
        }
        for (String match : searchMatches) {
            Matcher matcher = LOCATION_PATTERN.matcher(match == null ? "" : match);
            if (matcher.find()) {
                locations.add(new Location(normalizeFilePath(matcher.group(1)),
                        safeInt(matcher.group(2), 1),
                        match));
            }
        }
        return locations.stream()
                .filter(location -> !isBlank(location.filePath()))
                .distinct()
                .limit(16)
                .toList();
    }

    private List<String> inferSamePackageDependencies(String repository, List<CodeSnippetEntity> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return List.of();
        }
        Path repo = Path.of(repository).toAbsolutePath().normalize();
        Set<String> files = new LinkedHashSet<>();
        Set<String> existing = new LinkedHashSet<>();
        for (CodeSnippetEntity snippet : snippets) {
            if (snippet == null || isBlank(snippet.getFilePath())) {
                continue;
            }
            existing.add(normalizeFilePath(snippet.getFilePath()));
            String filePath = normalizeFilePath(snippet.getFilePath());
            int slash = filePath.lastIndexOf('/');
            if (slash < 0 || snippet.getLines() == null) {
                continue;
            }
            String packageDir = filePath.substring(0, slash + 1);
            for (String typeName : extractReferencedTypeNames(snippet.getLines())) {
                String dependencyFile = packageDir + typeName + ".java";
                Path dependencyPath = repo.resolve(dependencyFile).normalize();
                if (dependencyPath.startsWith(repo) && Files.exists(dependencyPath) && Files.isRegularFile(dependencyPath)) {
                    files.add(dependencyFile);
                }
            }
        }
        files.removeAll(existing);
        return files.stream().limit(8).toList();
    }

    private Set<String> extractReferencedTypeNames(List<String> lines) {
        Set<String> types = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*(?:Service|Repository|Client|Mapper|Request|Response|Config|Properties))\\b");
        for (String line : lines == null ? List.<String>of() : lines) {
            if (line == null || line.trim().startsWith("import ")) {
                continue;
            }
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                types.add(matcher.group(1));
            }
        }
        return types;
    }

    private List<String> findLikelyTests(String repository, List<String> candidateFiles) {
        if (candidateFiles == null || candidateFiles.isEmpty() || isBlank(repository)) {
            return List.of();
        }
        Path repo = Path.of(repository).toAbsolutePath().normalize();
        Path testRoot = repo.resolve("src/test");
        if (!Files.exists(testRoot)) {
            return List.of();
        }
        Set<String> expected = new LinkedHashSet<>();
        for (String file : candidateFiles) {
            String simpleName = simpleNameWithoutJava(file);
            if (!isBlank(simpleName)) {
                expected.add(simpleName + "Test.java");
                expected.add(simpleName + "Tests.java");
                expected.add(simpleName + "ConcurrencyTest.java");
            }
        }
        try (var paths = Files.walk(testRoot, 8)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> repo.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> expected.contains(Path.of(path).getFileName().toString()))
                    .distinct()
                    .limit(6)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> addBuildContext(String repository,
                                         List<CodeSnippetEntity> snippets,
                                         Map<String, String> reasons) {
        List<String> buildFiles = new ArrayList<>();
        for (String file : List.of("pom.xml", "src/main/resources/application.yml", "src/main/resources/application.properties")) {
            CodeSnippetEntity snippet = toolGateway.readFileSnippet(repository, file, 1, 220);
            if (Boolean.TRUE.equals(snippet.getAvailable())) {
                snippets.add(snippet);
                buildFiles.add(file);
                reasons.put(file, file.equals("pom.xml")
                        ? "build/test dependency context"
                        : "runtime configuration context");
            }
        }
        return buildFiles;
    }

    private List<String> inferSupportFiles(List<CodeSnippetEntity> snippets,
                                           List<String> primaryFiles,
                                           List<String> candidateFiles,
                                           List<String> relatedTests,
                                           List<String> buildFiles) {
        Set<String> supportFiles = new LinkedHashSet<>();
        Set<String> excluded = new LinkedHashSet<>();
        excluded.addAll(primaryFiles == null ? List.of() : primaryFiles);
        excluded.addAll(candidateFiles == null ? List.of() : candidateFiles);
        excluded.addAll(relatedTests == null ? List.of() : relatedTests);
        excluded.addAll(buildFiles == null ? List.of() : buildFiles);
        for (CodeSnippetEntity snippet : snippets == null ? List.<CodeSnippetEntity>of() : snippets) {
            String path = snippet == null ? "" : normalizeFilePath(snippet.getFilePath());
            if (!isBlank(path) && !excluded.contains(path)) {
                supportFiles.add(path);
            }
        }
        return supportFiles.stream().limit(8).toList();
    }

    private Map<String, Object> codeContextPackForPrompt(CodeContextPackEntity pack,
                                                         List<String> searchTerms,
                                                         List<String> searchMatches) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", pack.getStrategy());
        result.put("searchTerms", searchTerms == null ? List.of() : searchTerms);
        result.put("primaryFiles", pack.getPrimaryFiles() == null ? List.of() : pack.getPrimaryFiles());
        result.put("candidateFiles", pack.getCandidateFiles() == null ? List.of() : pack.getCandidateFiles());
        result.put("supportFiles", pack.getSupportFiles() == null ? List.of() : pack.getSupportFiles());
        result.put("relatedTests", pack.getRelatedTests() == null ? List.of() : pack.getRelatedTests());
        result.put("buildFiles", pack.getBuildFiles() == null ? List.of() : pack.getBuildFiles());
        result.put("contextReasons", pack.getContextReasons() == null ? Map.of() : pack.getContextReasons());
        result.put("availableSnippetCount", pack.getAvailableSnippetCount());
        result.put("totalSnippetCount", pack.getTotalSnippetCount());
        result.put("missingFiles", pack.getMissingFiles() == null ? List.of() : pack.getMissingFiles());
        result.put("searchMatchesPreview", searchMatches == null ? List.of() : searchMatches.stream().limit(12).toList());
        result.put("snippets", pack.getSnippets() == null ? List.of() : pack.getSnippets().stream()
                .filter(snippet -> Boolean.TRUE.equals(snippet.getAvailable()))
                .limit(12)
                .map(this::snippetForPrompt)
                .toList());
        return result;
    }

    private Map<String, Object> snippetForPrompt(CodeSnippetEntity snippet) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath", snippet.getFilePath());
        result.put("startLine", snippet.getStartLine());
        result.put("endLine", snippet.getEndLine());
        result.put("lines", limitSnippetLines(snippet.getLines(), 90, 9_000));
        return result;
    }

    private Map<String, Object> parseStructuredFinalAnswer(String finalAnswer) {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return Map.of();
        }
        try {
            JSONObject object = JSON.parseObject(extractJson(finalAnswer));
            Map<String, Object> result = new LinkedHashMap<>();
            object.forEach(result::put);
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<String> buildEvidence(AgentLoopResult result) {
        if (result == null) {
            return List.of("Agent loop did not return a result.");
        }
        List<String> evidence = new ArrayList<>();
        evidence.add("Agent loop status: " + result.getStatus());
        evidence.add("Agent loop turns: " + result.getTurns());
        evidence.add("Agent loop final answer: " + value(result.getFinalAnswer(), ""));
        if (result.getTrace() != null) {
            result.getTrace().stream()
                    .map(item -> item.getToolName() + " -> " + item.getToolStatus() + " (" + item.getSummary() + ")")
                    .forEach(evidence::add);
        }
        return evidence;
    }

    private List<String> extractJavaPaths(AgentLoopResult result) {
        List<String> values = new ArrayList<>();
        if (result == null) {
            return values;
        }
        collectJavaPaths(values, result.getFinalAnswer());
        if (result.getTrace() != null) {
            for (AgentLoopTraceItem item : result.getTrace()) {
                collectJavaPaths(values, item.getOutputPreview());
            }
        }
        if (result.getSteps() != null) {
            for (AgentLoopStep step : result.getSteps()) {
                collectJavaPaths(values, step.getToolResult() == null ? "" : String.valueOf(step.getToolResult().getOutput()));
            }
        }
        return values.stream().distinct().limit(20).toList();
    }

    private List<String> extractTestFiles(AgentLoopResult result) {
        return extractJavaPaths(result).stream()
                .filter(path -> path.contains("/src/test/") || path.contains("\\src\\test\\") || path.endsWith("Test.java") || path.endsWith("Tests.java"))
                .distinct()
                .limit(20)
                .toList();
    }

    private boolean shouldEnterCodeRepair(AgentLoopResult result, List<String> targetFiles) {
        if (result == null || !result.isSuccess() || targetFiles == null || targetFiles.isEmpty()) {
            return false;
        }
        String text = value(result.getFinalAnswer(), "").toLowerCase();
        if (text.contains("no code fix") || text.contains("do not modify code")
                || text.contains("configuration issue") || text.contains("runtime issue")
                || text.contains("capacity issue")) {
            return false;
        }
        return true;
    }

    private void collectJavaPaths(List<String> values, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = JAVA_PATH_PATTERN.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1).replace('\\', '/'));
        }
    }

    private void collectJavaPaths(Set<String> values, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = JAVA_PATH_PATTERN.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1).replace('\\', '/'));
        }
    }

    private List<String> listValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .distinct()
                    .limit(20)
                    .toList();
        }
        return List.of();
    }

    private List<String> firstNonEmptyList(List<String> first, List<String> second) {
        return first == null || first.isEmpty() ? (second == null ? List.of() : second) : first;
    }

    @SafeVarargs
    private final List<String> mergeDistinct(List<String>... lists) {
        List<String> merged = new ArrayList<>();
        if (lists == null) {
            return merged;
        }
        for (List<String> list : lists) {
            if (list == null) {
                continue;
            }
            for (String item : list) {
                if (item != null && !item.isBlank() && !merged.contains(item)) {
                    merged.add(item);
                }
            }
        }
        return merged.stream().limit(30).toList();
    }

    private String normalizeFixStrategy(String value, boolean shouldEnterCodeRepair) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.isBlank()) {
            return shouldEnterCodeRepair ? "CODE_FIX" : "NEED_MORE_EVIDENCE";
        }
        return switch (normalized) {
            case "CODE_FIX", "CONFIG_CHANGE", "SCALE_OR_RESOURCE", "ROLLBACK",
                    "DEPENDENCY_INCIDENT", "NEED_MORE_EVIDENCE", "NO_CODE_FIX" -> normalized;
            case "CONFIG_FIX" -> "CONFIG_CHANGE";
            case "CAPACITY_FIX", "RUNTIME_ACTION" -> "SCALE_OR_RESOURCE";
            default -> shouldEnterCodeRepair ? "CODE_FIX" : "NEED_MORE_EVIDENCE";
        };
    }

    private String normalizeScopeDecision(String value,
                                          List<String> targetFiles,
                                          List<String> targetMethods,
                                          String fixStrategy) {
        if (!"CODE_FIX".equals(fixStrategy)) {
            return "NO_CODE_FIX";
        }
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (List.of("STRICT_SINGLE_METHOD", "MULTI_METHOD", "FULL_FILE", "CROSS_FILE", "NO_CODE_FIX").contains(normalized)) {
            return normalized;
        }
        if (targetFiles != null && targetFiles.size() > 1) {
            return "CROSS_FILE";
        }
        if (targetMethods != null && targetMethods.size() > 1) {
            return "MULTI_METHOD";
        }
        return "STRICT_SINGLE_METHOD";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> candidateScope(Map<String, Object> structured,
                                               List<String> targetFiles,
                                               List<String> targetMethods,
                                               String scopeDecision,
                                               String fixStrategy) {
        Map<String, Object> scope = new LinkedHashMap<>();
        Object raw = structured.get("candidateScope");
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> scope.put(String.valueOf(key), value));
        }
        scope.putIfAbsent("targetFiles", targetFiles == null ? List.of() : targetFiles);
        scope.putIfAbsent("targetMethods", targetMethods == null ? List.of() : targetMethods);
        scope.putIfAbsent("scopeType", "CROSS_FILE".equals(scopeDecision) ? "FULL_FILE" : scopeDecision);
        scope.putIfAbsent("expandable", "CODE_FIX".equals(fixStrategy)
                && ("CROSS_FILE".equals(scopeDecision) || "MULTI_METHOD".equals(scopeDecision) || "FULL_FILE".equals(scopeDecision)));
        scope.putIfAbsent("expansionAllowedWhen", List.of(
                "direct caller/callee evidence proves root cause outside stack top",
                "a helper method is needed to make the fix atomic or reusable",
                "tests fail because the initial target alone cannot fix behavior"));
        return scope;
    }

    private Map<String, Object> localizationDecision(Map<String, Object> output) {
        Map<String, Object> decision = new LinkedHashMap<>();
        for (String key : List.of(
                "summary", "fixStrategy", "strategyType", "scopeDecisionType", "rootCauseLocationType",
                "primarySymptomLocation", "directEvidenceFiles", "relatedFiles", "rootCauseCandidateFiles",
                "doNotModifyFiles", "targetFiles", "targetMethods", "candidateFiles", "candidateMethods",
                "suspectedRootCauseLocations", "candidateScope", "supportingCodeEvidence", "negativeEvidence",
                "reasoning", "shouldEnterCodeRepair", "localizationConfidence", "missingEvidence")) {
            decision.put(key, output.getOrDefault(key, ""));
        }
        return decision;
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String extractJson(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String value(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalizeFilePath(String filePath) {
        if (isBlank(filePath)) {
            return "";
        }
        String normalized = filePath.trim().replace('\\', '/');
        int srcIndex = normalized.indexOf("src/");
        if (srcIndex >= 0) {
            normalized = normalized.substring(srcIndex);
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private String lastPathSegment(String value) {
        if (isBlank(value)) {
            return "";
        }
        String normalized = value.trim();
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private boolean isNoisySearchTerm(String term) {
        String lower = term == null ? "" : term.toLowerCase(Locale.ROOT);
        return Set.of("http", "https", "post", "get", "api", "true", "false",
                "null", "error", "failed", "failure", "service", "method", "class").contains(lower);
    }

    private int safeInt(String value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String simpleNameWithoutJava(String filePath) {
        String normalized = normalizeFilePath(filePath);
        if (isBlank(normalized) || !normalized.endsWith(".java")) {
            return "";
        }
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return fileName.substring(0, fileName.length() - ".java".length());
    }

    private List<String> limitSnippetLines(List<String> lines, int maxLines, int maxChars) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        int chars = 0;
        for (String line : lines) {
            if (result.size() >= maxLines || chars >= maxChars) {
                break;
            }
            String value = line == null ? "" : line;
            result.add(value);
            chars += value.length();
        }
        return result;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength)) + "...";
    }

    private record Location(String filePath, int line, String raw) {
    }

}
