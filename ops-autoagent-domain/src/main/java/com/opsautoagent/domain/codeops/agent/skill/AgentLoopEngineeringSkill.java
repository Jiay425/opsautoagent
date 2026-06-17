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
    private static final int DEFAULT_CONTEXT_BUDGET_CHARS = 28_000;
    private static final int DEFAULT_SNIPPET_BUDGET_CHARS = 4_000;

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
        Map<String, Object> localizationQuality = localizationQuality(rawOutput, result);
        Map<String, Object> localizationReflection = localizationReflection(rawOutput, localizationQuality, preLoopCodeContextPack);
        rawOutput.put("localizationQuality", localizationQuality);
        rawOutput.put("localizationReflection", localizationReflection);
        rawOutput.put("localizationReflectionRequired", Boolean.TRUE.equals(localizationReflection.get("required")));
        rawOutput.put("localizationBlocking", Boolean.TRUE.equals(localizationReflection.get("blocking")));
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
        output.put("repairPlan", repairPlan(structured, output, targetFiles, targetMethods, scopeDecision, fixStrategy));
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
        return codeContextPackForPrompt(pack, searchTerms, searchMatches, resolveContextBudget(task));
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
                                                         List<String> searchMatches,
                                                         Map<String, Object> contextBudget) {
        Map<String, Object> result = new LinkedHashMap<>();
        ContextBudgetState budget = new ContextBudgetState(
                intValue(contextBudget.get("maxPromptChars"), DEFAULT_CONTEXT_BUDGET_CHARS),
                intValue(contextBudget.get("maxSnippetChars"), DEFAULT_SNIPPET_BUDGET_CHARS));
        result.put("strategy", pack.getStrategy());
        result.put("contextBudget", contextBudget);
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
        result.put("snippets", budgetedSnippets(pack, budget));
        result.put("budgetSummary", budget.summary());
        return result;
    }

    private List<Map<String, Object>> budgetedSnippets(CodeContextPackEntity pack, ContextBudgetState budget) {
        if (pack.getSnippets() == null || pack.getSnippets().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        List<CodeSnippetEntity> ordered = orderSnippetsForBudget(pack);
        for (CodeSnippetEntity snippet : ordered) {
            if (!Boolean.TRUE.equals(snippet.getAvailable())) {
                continue;
            }
            Map<String, Object> item = snippetForPrompt(snippet, budget, snippetRole(pack, snippet.getFilePath()));
            if (!item.isEmpty()) {
                result.add(item);
            }
            if (budget.remainingChars <= 0) {
                break;
            }
        }
        return result;
    }

    private List<CodeSnippetEntity> orderSnippetsForBudget(CodeContextPackEntity pack) {
        List<CodeSnippetEntity> snippets = new ArrayList<>(pack.getSnippets());
        snippets.sort((left, right) -> Integer.compare(
                snippetPriority(pack, left.getFilePath()),
                snippetPriority(pack, right.getFilePath())));
        return snippets;
    }

    private int snippetPriority(CodeContextPackEntity pack, String filePath) {
        String normalized = normalizeFilePath(filePath);
        if (containsFile(pack.getPrimaryFiles(), normalized)) return 1;
        if (containsFile(pack.getCandidateFiles(), normalized)) return 2;
        if (containsFile(pack.getSupportFiles(), normalized)) return 3;
        if (containsFile(pack.getRelatedTests(), normalized)) return 4;
        if (containsFile(pack.getBuildFiles(), normalized)) return 5;
        return 9;
    }

    private String snippetRole(CodeContextPackEntity pack, String filePath) {
        String normalized = normalizeFilePath(filePath);
        if (containsFile(pack.getPrimaryFiles(), normalized)) return "PRIMARY";
        if (containsFile(pack.getCandidateFiles(), normalized)) return "CANDIDATE";
        if (containsFile(pack.getSupportFiles(), normalized)) return "SUPPORT";
        if (containsFile(pack.getRelatedTests(), normalized)) return "TEST";
        if (containsFile(pack.getBuildFiles(), normalized)) return "BUILD_OR_CONFIG";
        return "EXTRA";
    }

    private boolean containsFile(List<String> files, String target) {
        if (files == null || target == null) {
            return false;
        }
        return files.stream().map(this::normalizeFilePath).anyMatch(target::equals);
    }

    private Map<String, Object> snippetForPrompt(CodeSnippetEntity snippet,
                                                 ContextBudgetState budget,
                                                 String role) {
        if (budget.remainingChars <= 0) {
            budget.truncatedSnippetCount++;
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath", snippet.getFilePath());
        result.put("role", role);
        result.put("startLine", snippet.getStartLine());
        result.put("endLine", snippet.getEndLine());
        List<String> lines = limitSnippetLines(snippet.getLines(), 90, Math.min(budget.maxSnippetChars, budget.remainingChars));
        int chars = lines.stream().mapToInt(line -> line == null ? 0 : line.length()).sum();
        boolean truncated = snippet.getLines() != null && lines.size() < snippet.getLines().size();
        result.put("lines", lines);
        result.put("truncated", truncated);
        budget.remainingChars -= chars;
        budget.includedSnippetCount++;
        budget.includedChars += chars;
        if (truncated) {
            budget.truncatedSnippetCount++;
        }
        return result;
    }

    private Map<String, Object> resolveContextBudget(EngineeringTaskEntity task) {
        Map<String, Object> budget = new LinkedHashMap<>();
        int maxPromptChars = DEFAULT_CONTEXT_BUDGET_CHARS;
        int maxSnippetChars = DEFAULT_SNIPPET_BUDGET_CHARS;
        if (task != null && task.getContext() != null) {
            maxPromptChars = intValue(task.getContext().get("codeContextMaxPromptChars"), maxPromptChars);
            maxSnippetChars = intValue(task.getContext().get("codeContextMaxSnippetChars"), maxSnippetChars);
        }
        budget.put("maxPromptChars", Math.max(8_000, Math.min(maxPromptChars, 80_000)));
        budget.put("maxSnippetChars", Math.max(1_000, Math.min(maxSnippetChars, 12_000)));
        budget.put("policy", List.of(
                "PRIMARY source snippets first",
                "candidate root-cause snippets second",
                "same-package dependencies third",
                "related tests and build/config context last",
                "large snippets are truncated with budgetSummary recorded"));
        return budget;
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
                "suspectedRootCauseLocations", "candidateScope", "repairPlan", "supportingCodeEvidence", "negativeEvidence",
                "reasoning", "shouldEnterCodeRepair", "localizationConfidence", "missingEvidence")) {
            decision.put(key, output.getOrDefault(key, ""));
        }
        return decision;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> repairPlan(Map<String, Object> structured,
                                           Map<String, Object> output,
                                           List<String> targetFiles,
                                           List<String> targetMethods,
                                           String scopeDecision,
                                           String fixStrategy) {
        Map<String, Object> plan = new LinkedHashMap<>();
        Object raw = structured.get("repairPlan");
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> plan.put(String.valueOf(key), value));
        }
        plan.putIfAbsent("intent", value(stringValue(structured.get("summary")),
                "Investigate incident and decide whether a code repair is required"));
        plan.put("fixStrategy", fixStrategy);
        plan.put("scopeDecision", scopeDecision);
        plan.putIfAbsent("rootCauseHypothesis", value(stringValue(structured.get("reasoning")),
                stringValue(structured.get("summary"))));
        plan.put("targetFiles", targetFiles == null ? List.of() : targetFiles);
        plan.put("targetMethods", targetMethods == null ? List.of() : targetMethods);
        plan.put("candidateFiles", output.getOrDefault("candidateFiles", List.of()));
        plan.put("directEvidenceFiles", output.getOrDefault("directEvidenceFiles", List.of()));
        plan.put("rootCauseCandidateFiles", output.getOrDefault("rootCauseCandidateFiles", List.of()));
        plan.put("doNotModifyFiles", output.getOrDefault("doNotModifyFiles", List.of()));
        plan.put("candidateScope", output.getOrDefault("candidateScope", Map.of()));
        plan.put("shouldEnterCodeRepair", output.getOrDefault("shouldEnterCodeRepair", Boolean.FALSE));
        plan.putIfAbsent("verificationPlan", defaultVerificationPlan(targetFiles, fixStrategy));
        plan.putIfAbsent("riskPlan", defaultRiskPlan(fixStrategy, scopeDecision));
        plan.put("missingEvidence", output.getOrDefault("missingEvidence", List.of()));
        return plan;
    }

    private List<String> defaultVerificationPlan(List<String> targetFiles, String fixStrategy) {
        if (!"CODE_FIX".equals(fixStrategy)) {
            return List.of("No code patch verification required; validate operational mitigation and observability.");
        }
        List<String> plan = new ArrayList<>();
        plan.add("Apply the smallest patch inside repairPlan target/candidate scope.");
        plan.add("Run Maven compile gate after patch application.");
        plan.add("Run related unit/regression tests for target files.");
        if (targetFiles != null && targetFiles.size() > 1) {
            plan.add("Verify cross-file behavior with a regression test covering the caller and root-cause component.");
        }
        return plan;
    }

    private List<String> defaultRiskPlan(String fixStrategy, String scopeDecision) {
        if (!"CODE_FIX".equals(fixStrategy)) {
            return List.of("Check runtime/config rollback path.", "Observe metrics/logs/traces for the affected endpoint.");
        }
        return List.of(
                "Review patch minimality and modified method/file boundaries.",
                "Check whether the selected scopeDecision=" + value(scopeDecision, "UNKNOWN") + " requires human approval.",
                "Define post-release metrics, logs, and rollback trigger.");
    }

    private Map<String, Object> localizationQuality(Map<String, Object> rawOutput, AgentLoopResult result) {
        Map<String, Object> quality = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        List<String> strengths = new ArrayList<>();
        String confidence = stringValue(rawOutput.get("localizationConfidence")).toUpperCase(Locale.ROOT);
        String fixStrategy = stringValue(rawOutput.get("fixStrategy")).toUpperCase(Locale.ROOT);
        String scopeDecision = stringValue(rawOutput.get("scopeDecisionType")).toUpperCase(Locale.ROOT);
        List<String> targetFiles = listValue(rawOutput.get("targetFiles"));
        List<String> targetMethods = listValue(rawOutput.get("targetMethods"));
        List<String> rootCauseFiles = listValue(rawOutput.get("rootCauseCandidateFiles"));
        List<String> directEvidenceFiles = listValue(rawOutput.get("directEvidenceFiles"));
        List<String> supportingCodeEvidence = listValue(rawOutput.get("supportingCodeEvidence"));
        List<String> missingEvidence = listValue(rawOutput.get("missingEvidence"));

        if ("HIGH".equals(confidence)) {
            strengths.add("localization confidence is HIGH");
        } else if ("LOW".equals(confidence) || confidence.isBlank()) {
            issues.add("localization confidence is not strong enough");
        }
        if (targetFiles.isEmpty() && "CODE_FIX".equals(fixStrategy)) {
            issues.add("CODE_FIX selected but targetFiles is empty");
        } else if (!targetFiles.isEmpty()) {
            strengths.add("targetFiles present");
        }
        if (targetMethods.isEmpty() && "STRICT_SINGLE_METHOD".equals(scopeDecision)) {
            issues.add("STRICT_SINGLE_METHOD selected but targetMethods is empty");
        } else if (!targetMethods.isEmpty()) {
            strengths.add("targetMethods present");
        }
        if ("CODE_FIX".equals(fixStrategy) && rootCauseFiles.isEmpty()) {
            issues.add("CODE_FIX selected but rootCauseCandidateFiles is empty");
        }
        if (directEvidenceFiles.isEmpty() && rootCauseFiles.size() > 1) {
            issues.add("multiple root-cause candidates without directEvidenceFiles");
        }
        if (supportingCodeEvidence.isEmpty() && "CODE_FIX".equals(fixStrategy)) {
            issues.add("CODE_FIX selected without supportingCodeEvidence");
        }
        if (!missingEvidence.isEmpty()) {
            issues.add("missingEvidence is not empty: " + String.join("; ", missingEvidence));
        }
        if (result != null && "MAX_TURNS_REACHED".equalsIgnoreCase(result.getStatus())) {
            issues.add("agent loop reached max turns before final localization");
        }
        boolean shouldEnterRepair = Boolean.TRUE.equals(rawOutput.get("shouldEnterCodeRepair"));
        if (!"CODE_FIX".equals(fixStrategy) && shouldEnterRepair) {
            issues.add("non-code fix strategy still allows code repair");
        }

        int score = Math.max(0, 100 - issues.size() * 18);
        if ("HIGH".equals(confidence)) {
            score = Math.min(100, score + 8);
        }
        if (!supportingCodeEvidence.isEmpty()) {
            score = Math.min(100, score + 6);
        }
        quality.put("score", score);
        quality.put("confidence", confidence);
        quality.put("issues", issues);
        quality.put("strengths", strengths);
        quality.put("shouldReflect", score < 75 || !missingEvidence.isEmpty() || "NEED_MORE_EVIDENCE".equals(fixStrategy));
        quality.put("shouldBlockCodeRepair", "CODE_FIX".equals(fixStrategy)
                && (targetFiles.isEmpty() || rootCauseFiles.isEmpty() || score < 45));
        return quality;
    }

    private Map<String, Object> localizationReflection(Map<String, Object> rawOutput,
                                                       Map<String, Object> quality,
                                                       Map<String, Object> preLoopCodeContextPack) {
        boolean required = Boolean.TRUE.equals(quality.get("shouldReflect"));
        boolean blocking = Boolean.TRUE.equals(quality.get("shouldBlockCodeRepair"));
        List<String> issues = listValue(quality.get("issues"));
        Map<String, Object> reflection = new LinkedHashMap<>();
        reflection.put("required", required);
        reflection.put("blocking", blocking);
        reflection.put("reason", required
                ? "localization quality gate found unresolved evidence or low confidence"
                : "localization quality is sufficient");
        reflection.put("issues", issues);
        reflection.put("mustVerify", localizationMustVerify(rawOutput, issues));
        reflection.put("suggestedToolCalls", required ? localizationSuggestedToolCalls(rawOutput, preLoopCodeContextPack) : List.of());
        reflection.put("nextAttemptConstraints", required ? List.of(
                "Do not generate a patch until rootCauseCandidateFiles and fixStrategy are justified by code or observability evidence.",
                "If stack top is only a caller, expand through direct callees before deciding repair scope.",
                "If the incident is runtime/config/capacity/dependency, return NO_CODE_FIX or NEED_MORE_EVIDENCE instead of forcing CODE_FIX.") : List.of());
        return reflection;
    }

    private List<String> localizationMustVerify(Map<String, Object> rawOutput, List<String> issues) {
        List<String> mustVerify = new ArrayList<>();
        String fixStrategy = stringValue(rawOutput.get("fixStrategy"));
        String scopeDecision = stringValue(rawOutput.get("scopeDecisionType"));
        mustVerify.add("fixStrategy=" + value(fixStrategy, "UNKNOWN"));
        mustVerify.add("scopeDecision=" + value(scopeDecision, "UNKNOWN"));
        if (issues.stream().anyMatch(issue -> issue.contains("targetFiles") || issue.contains("rootCauseCandidateFiles"))) {
            mustVerify.add("root cause source file and whether stack-top file is only a caller");
        }
        if (issues.stream().anyMatch(issue -> issue.contains("targetMethods"))) {
            mustVerify.add("method-level repair boundary");
        }
        if (issues.stream().anyMatch(issue -> issue.contains("supportingCodeEvidence"))) {
            mustVerify.add("code fact supporting the selected root cause");
        }
        if (issues.stream().anyMatch(issue -> issue.contains("missingEvidence"))) {
            mustVerify.add("missing evidence listed by the first localization pass");
        }
        return mustVerify.stream().distinct().toList();
    }

    private List<Map<String, Object>> localizationSuggestedToolCalls(Map<String, Object> rawOutput,
                                                                     Map<String, Object> preLoopCodeContextPack) {
        List<Map<String, Object>> calls = new ArrayList<>();
        List<String> targetFiles = mergeDistinct(
                listValue(rawOutput.get("targetFiles")),
                listValue(rawOutput.get("rootCauseCandidateFiles")),
                listValue(rawOutput.get("directEvidenceFiles")));
        for (String file : targetFiles.stream().limit(3).toList()) {
            calls.add(Map.of(
                    "toolName", "repo.read_file_snippet",
                    "arguments", Map.of("filePath", file, "centerLine", 1, "radius", 260),
                    "purpose", "verify full candidate file before repair scope is finalized"));
        }
        List<String> searchTerms = listValue(preLoopCodeContextPack == null ? null : preLoopCodeContextPack.get("searchTerms"));
        if (!searchTerms.isEmpty()) {
            calls.add(Map.of(
                    "toolName", "repo.search_text",
                    "arguments", Map.of("queries", searchTerms.stream().limit(6).toList(), "maxMatches", 30),
                    "purpose", "re-check alert-derived search terms and caller/callee evidence"));
        }
        if (calls.isEmpty()) {
            calls.add(Map.of(
                    "toolName", "repo.search_text",
                    "arguments", Map.of("queries", List.of("exception", "requestId", "timeout", "error"), "maxMatches", 30),
                    "purpose", "collect first-pass repository evidence because localization has no usable target"));
        }
        return calls;
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

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
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

    private static final class ContextBudgetState {
        private final int maxPromptChars;
        private final int maxSnippetChars;
        private int remainingChars;
        private int includedChars;
        private int includedSnippetCount;
        private int truncatedSnippetCount;

        private ContextBudgetState(int maxPromptChars, int maxSnippetChars) {
            this.maxPromptChars = maxPromptChars;
            this.maxSnippetChars = maxSnippetChars;
            this.remainingChars = maxPromptChars;
        }

        private Map<String, Object> summary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("maxPromptChars", maxPromptChars);
            summary.put("maxSnippetChars", maxSnippetChars);
            summary.put("includedChars", includedChars);
            summary.put("remainingChars", Math.max(0, remainingChars));
            summary.put("includedSnippetCount", includedSnippetCount);
            summary.put("truncatedSnippetCount", truncatedSnippetCount);
            return summary;
        }
    }

}
