package com.opsautoagent.domain.codeops.agent.recovery;

import com.alibaba.fastjson.JSON;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.FailureDiagnosticEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FailureDiagnosticParserService {

    private static final Pattern JAVA_FILE_PATTERN = Pattern.compile("([A-Za-z0-9_./\\\\-]+\\.java)(?::\\[(\\d+),\\d+]|:(\\d+))?");
    private static final Pattern MAVEN_COMMAND_PATTERN = Pattern.compile("mvn(?:\\.cmd)?\\s+[-A-Za-z0-9_:=./\\\\\\s]+");
    private static final Pattern COMPILE_ERROR_PATTERN = Pattern.compile("(?i)([A-Za-z0-9_./\\\\-]+\\.java):\\[?(\\d+)(?:,\\d+)?]?\\s+(.*)");
    private static final Pattern ASSERTION_PATTERN = Pattern.compile("(?i)expected:?\\s*[<\\[]([^>\\]]*)[>\\]]\\s*(?:but\\s*)?(?:was|actual):?\\s*[<\\[]([^>\\]]*)[>\\]]");
    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile("\\s*at\\s+([A-Za-z0-9_.$]+)\\.([A-Za-z0-9_$<>]+)\\(([^:()]+\\.java):(\\d+)\\)");

    public FailureDiagnosticEntity parse(int round, String skillId, EngineeringSkillResultEntity result) {
        Map<String, Object> rawOutput = result == null || result.getRawOutput() == null ? Map.of() : result.getRawOutput();
        String text = JSON.toJSONString(rawOutput).toLowerCase(Locale.ROOT);
        String reportedTestFailureType = stringValue(rawOutput.get("testFailureType"), "");
        String failureType = "UNKNOWN";
        List<String> failedFiles = new ArrayList<>();
        List<String> failedMethods = new ArrayList<>();
        List<String> failedCommands = new ArrayList<>();
        List<String> mustFix = new ArrayList<>();
        List<String> mustAvoid = new ArrayList<>();
        List<String> nextAttemptConstraints = new ArrayList<>();
        List<Map<String, Object>> compileErrors = new ArrayList<>();
        List<Map<String, Object>> testAssertions = new ArrayList<>();
        List<Map<String, Object>> stackTraceFrames = new ArrayList<>();
        boolean verificationBlocked = false;
        String verificationBlockedReason = "";

        Object scopeGuard = rawOutput.get("patchScopeGuard");
        if (scopeGuard instanceof Map<?, ?> guardMap && Boolean.FALSE.equals(guardMap.get("passed"))) {
            failureType = "SCOPE_GUARD_FAILED";
            String guardFailureType = stringValue(guardMap.get("failureType"), "METHOD_OUT_OF_SCOPE");
            if ("SCOPE_EXPANSION_OUT_OF_BOUND".equals(guardFailureType)
                    || "HALLUCINATED_SCOPE".equals(guardFailureType)) {
                mustFix.add("Scope expansion failed. If the changed files are inside repairScope.candidateScope.targetFiles, use explicit EXPAND_SCOPE/FULL_FILE inside candidateScope.");
                nextAttemptConstraints.add("You may broaden only inside repairScope.candidateScope.targetFiles. Do not touch files outside candidateScope.");
            } else {
                mustFix.add("Only modify methods listed in repairScope.targetMethods. Guard failure: " + guardFailureType);
                nextAttemptConstraints.add("Do NOT modify any method outside repairScope.targetMethods.");
            }
            addList(mustAvoid, guardMap.get("violations"));
            addList(failedMethods, guardMap.get("changedMethods"));
            addList(failedFiles, guardMap.get("touchedFiles"));
            Object rs = guardMap.get("repairScope");
            if (rs instanceof Map<?, ?> rsm) {
                mustFix.add("repairScope scopeType=" + rsm.get("scopeType") + ", targetMethods=" + rsm.get("targetMethods"));
            }
        }

        Object patchApply = rawOutput.get("patchApply");
        Object exactReplaceApply = rawOutput.get("exactReplaceApply");
        if (exactReplaceApply instanceof Map<?, ?> exactMap
                && Boolean.TRUE.equals(exactMap.get("requested"))
                && Boolean.FALSE.equals(exactMap.get("success"))) {
            failureType = firstFailureType(failureType, "EXACT_REPLACE_FAILED");
            mustFix.add("Exact replace block did not match current file content. Re-read the target file and regenerate oldText from current source.");
            mustAvoid.add("Do not retry the same oldText block after an exact replace mismatch.");
            nextAttemptConstraints.add("Use repo.read_file_snippet before the next edit attempt and copy oldText byte-for-byte.");
            addList(failedFiles, exactMap.get("appliedFiles"));
            addList(mustFix, exactMap.get("failures"));
        }

        if (patchApply instanceof Map<?, ?> paMap
                && Boolean.TRUE.equals(paMap.get("requested"))
                && Boolean.FALSE.equals(paMap.get("applied"))) {
            failureType = firstFailureType(failureType, "PATCH_APPLY_FAILED");
            mustFix.add("Patch did not apply. Prefer exact replace blocks or complete fileRewrites based on freshly read source.");
            mustAvoid.add("Do not keep retrying the same stale unifiedDiffPatch.");
            nextAttemptConstraints.add("Re-read the target file before the next patch attempt.");
            addTextFiles(failedFiles, stringValue(paMap.get("output"), "") + "\n" + stringValue(paMap.get("errorMessage"), ""));
        }

        Object sourceValidation = rawOutput.get("sourceValidation");
        if (sourceValidation instanceof Map<?, ?> svMap && Boolean.FALSE.equals(svMap.get("valid"))) {
            failureType = firstFailureType(failureType, "SOURCE_STRUCTURE_INVALID");
            addList(mustFix, svMap.get("errors"));
            mustAvoid.add("Do not emit unbalanced braces or content after the final Java class brace.");
            addTextFiles(failedFiles, text);
        }

        Object compileGate = rawOutput.get("compileGate");
        if (compileGate instanceof Map<?, ?> cgMap
                && Boolean.TRUE.equals(cgMap.get("requested"))
                && Boolean.FALSE.equals(cgMap.get("success"))) {
            failureType = firstFailureType(failureType, "COMPILE_FAILED");
            mustFix.add("Production code does not compile. Fix exact compiler errors before changing behavior.");
            Object command = cgMap.get("command");
            if (command instanceof List<?> list) {
                failedCommands.add(String.join(" ", list.stream().map(String::valueOf).toList()));
            } else if (command != null) {
                failedCommands.add(String.valueOf(command));
            }
            String output = stringValue(cgMap.get("output"), "");
            addTextFiles(failedFiles, output);
            compileErrors.addAll(extractStructuredCompileErrors(output));
            mustFix.addAll(compileErrors.stream()
                    .map(error -> String.valueOf(error.getOrDefault("message", "")))
                    .filter(message -> !message.isBlank())
                    .toList());
            stackTraceFrames.addAll(extractStackTraceFrames(output));
            nextAttemptConstraints.add("Compile must pass before test/risk stages are trusted.");
        }

        Object testPatchApply = rawOutput.get("testPatchApply");
        if (testPatchApply instanceof Map<?, ?> tpaMap
                && Boolean.TRUE.equals(tpaMap.get("requested"))
                && Boolean.FALSE.equals(tpaMap.get("applied"))) {
            failureType = firstFailureType(failureType, "TEST_PATCH_APPLY_FAILED");
            mustFix.add("Test patch did not apply. Re-read test files and use exact replace/fileRewrite with valid package/imports.");
            mustAvoid.add("Do not generate test patches for non-existent directories.");
        }

        if ("test_verification".equals(skillId)) {
            boolean hasAssertionFailure = text.contains("assertion")
                    || text.contains("expected:")
                    || text.contains("expected")
                    || text.contains("but was:")
                    || text.contains("<<< failure!")
                    || text.contains("failures:");
            boolean noMatchingTests = text.contains("no tests matching pattern") || text.contains("no tests were executed");
            boolean hasTestCompileFailure = text.contains("testcompile")
                    || text.contains("compilation failure")
                    || text.contains("compilation error")
                    || text.contains("cannot find symbol")
                    || text.contains("does not exist");
            if (noMatchingTests) {
                failureType = firstFailureType(failureType, "VERIFICATION_BLOCKED");
                verificationBlocked = true;
                verificationBlockedReason = "No tests matching the requested selector were executed.";
                mustFix.add("Verification is blocked because the targeted test does not exist or was not applied.");
                nextAttemptConstraints.add("Do not treat missing tests as a production behavior failure; apply/create the test or filter the selector.");
            } else if ("TEST_ASSERTION_FAILED".equalsIgnoreCase(reportedTestFailureType) || hasAssertionFailure) {
                failureType = firstFailureType(failureType, "TEST_ASSERTION_FAILED");
                mustFix.add("Tests executed but assertions failed. Align implementation behavior with the failing assertion.");
                addAssertionRequirements(text, mustFix, nextAttemptConstraints);
                addTextFiles(failedFiles, text);
                testAssertions.addAll(extractAssertions(text));
                stackTraceFrames.addAll(extractStackTraceFrames(text));
            } else if ("TEST_COMPILE_FAILED".equalsIgnoreCase(reportedTestFailureType) || hasTestCompileFailure) {
                failureType = firstFailureType(failureType, "TEST_COMPILE_FAILED");
                mustFix.add("Generated tests do not compile. Match visible production APIs and avoid missing dependencies.");
                addMissingApiRequirements(text, mustFix, nextAttemptConstraints);
                addTextFiles(failedFiles, text);
                compileErrors.addAll(extractStructuredCompileErrors(text));
                stackTraceFrames.addAll(extractStackTraceFrames(text));
            } else if (text.contains("command timeout") || text.contains("timed out")) {
                failureType = firstFailureType(failureType, "TEST_TIMEOUT");
                mustFix.add("A Maven verification command timed out. Ensure tests have bounded execution time.");
                mustAvoid.add("Do not use unbounded waits or infinite loops in tests.");
            }
        }

        Map<String, Object> repositoryOutput = rawOutput;
        String reportRepository = firstNonBlank(
                stringValue(repositoryOutput.get("testExecutionRepositoryPath"), ""),
                stringValue(repositoryOutput.get("sandboxRepositoryPath"), ""),
                stringValue(repositoryOutput.get("repositoryPath"), ""));
        List<Map<String, Object>> surefireFailures = readSurefireFailures(reportRepository);
        if (!surefireFailures.isEmpty()) {
            testAssertions.addAll(surefireFailures);
            for (Map<String, Object> failure : surefireFailures) {
                addIfPresent(failedFiles, failure.get("file"));
                addIfPresent(mustFix, failure.get("message"));
            }
            if ("UNKNOWN".equals(failureType)) {
                failureType = "TEST_ASSERTION_FAILED";
            }
        }

        failedCommands.addAll(extractMavenCommands(text));
        return FailureDiagnosticEntity.builder()
                .round(round)
                .failedSkill(skillId)
                .failureType(failureType)
                .failedFiles(distinct(failedFiles))
                .failedMethods(distinct(failedMethods))
                .failedCommands(distinct(failedCommands))
                .mustFix(distinct(mustFix))
                .mustAvoid(distinct(mustAvoid))
                .nextAttemptConstraints(distinct(nextAttemptConstraints))
                .repairScope(extractRepairScope(rawOutput))
                .modelRouting(mapValue(rawOutput.get("modelRouting")))
                .rawFailureSummary(abbreviate(result == null ? "" : result.getSummary(), 1500))
                .compileErrors(distinctMaps(compileErrors))
                .testAssertions(distinctMaps(testAssertions))
                .stackTraceFrames(distinctMaps(stackTraceFrames))
                .verificationBlocked(verificationBlocked)
                .verificationBlockedReason(verificationBlockedReason)
                .build();
    }

    private String firstFailureType(String current, String next) {
        return current == null || current.isBlank() || "UNKNOWN".equals(current) ? next : current;
    }

    private void addMissingApiRequirements(String failureText, List<String> mustFix, List<String> constraints) {
        String lower = failureText == null ? "" : failureText.toLowerCase(Locale.ROOT);
        if (lower.contains("trymarkprocessed") && lower.contains("idempotencyservice")) {
            mustFix.add("IdempotencyServiceAtomicityTest requires IdempotencyService.tryMarkProcessed(String). Implement atomic check-and-mark in IdempotencyService and update caller.");
            constraints.add("Use scope expansion inside candidateScope for IdempotencyService and OrderSubmitService.");
        }
    }

    private void addAssertionRequirements(String failureText, List<String> mustFix, List<String> constraints) {
        String lower = failureText == null ? "" : failureText.toLowerCase(Locale.ROOT);
        if (lower.contains("trymarkprocessed") && lower.contains("idempotencyservice")) {
            mustFix.add("IdempotencyService.tryMarkProcessed(String) assertion failed. It must return true for exactly one first-time caller of a requestId and false for duplicates.");
            constraints.add("Keep the check-and-mark operation atomic; use a single atomic collection operation instead of a separate contains/add sequence.");
            if (lower.contains("expected:\\u003c1\\u003e but was:\\u003c0\\u003e")
                    || lower.contains("expected: <1> but was: <0>")) {
                mustFix.add("The current implementation appears to return false even for the first caller. Verify return semantics of the atomic map/set operation.");
            }
        }
    }

    private List<Map<String, Object>> extractStructuredCompileErrors(String output) {
        List<Map<String, Object>> errors = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return errors;
        }
        for (String line : output.split("\\R")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("[error]") || lower.contains("compilation failure") || lower.contains("cannot find symbol")) {
                Map<String, Object> error = new LinkedHashMap<>();
                Matcher matcher = COMPILE_ERROR_PATTERN.matcher(line.replace("[ERROR]", "").trim());
                if (matcher.find()) {
                    error.put("file", matcher.group(1).replace('\\', '/'));
                    error.put("line", parseInt(matcher.group(2)));
                    error.put("message", abbreviate(matcher.group(3).trim(), 240));
                    addSymbolHints(error, matcher.group(3));
                } else {
                    error.put("file", "");
                    error.put("line", 0);
                    error.put("message", abbreviate(line.trim(), 240));
                }
                errors.add(error);
            }
            if (errors.size() >= 12) {
                break;
            }
        }
        return errors;
    }

    private void addSymbolHints(Map<String, Object> error, String message) {
        String text = message == null ? "" : message;
        Matcher symbol = Pattern.compile("symbol:\\s+(?:method|variable|class)\\s+([^\\s]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (symbol.find()) {
            error.put("symbol", symbol.group(1));
        }
        Matcher required = Pattern.compile("required:\\s*([^,;]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (required.find()) {
            error.put("requiredType", required.group(1).trim());
        }
        Matcher found = Pattern.compile("found:\\s*([^,;]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (found.find()) {
            error.put("foundType", found.group(1).trim());
        }
    }

    private List<Map<String, Object>> extractAssertions(String text) {
        List<Map<String, Object>> assertions = new ArrayList<>();
        Matcher matcher = ASSERTION_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find() && assertions.size() < 12) {
            Map<String, Object> assertion = new LinkedHashMap<>();
            assertion.put("expected", matcher.group(1));
            assertion.put("actual", matcher.group(2));
            assertions.add(assertion);
        }
        return assertions;
    }

    private List<Map<String, Object>> extractStackTraceFrames(String text) {
        List<Map<String, Object>> frames = new ArrayList<>();
        Matcher matcher = STACK_FRAME_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find() && frames.size() < 20) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("className", matcher.group(1));
            frame.put("methodName", matcher.group(2));
            frame.put("file", matcher.group(3));
            frame.put("line", parseInt(matcher.group(4)));
            frames.add(frame);
        }
        return frames;
    }

    private List<Map<String, Object>> readSurefireFailures(String repositoryPath) {
        if (repositoryPath == null || repositoryPath.isBlank()) {
            return List.of();
        }
        Path reportDir = Path.of(repositoryPath).toAbsolutePath().normalize().resolve("target/surefire-reports");
        if (!Files.exists(reportDir) || !Files.isDirectory(reportDir)) {
            return List.of();
        }
        List<Map<String, Object>> failures = new ArrayList<>();
        try (var paths = Files.list(reportDir)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().startsWith("TEST-"))
                    .filter(file -> file.getFileName().toString().endsWith(".xml"))
                    .toList()) {
                String xml = Files.readString(path, StandardCharsets.UTF_8);
                failures.addAll(parseSurefireXml(path, xml));
                if (failures.size() >= 20) {
                    break;
                }
            }
        } catch (Exception ignored) {
            return failures;
        }
        return failures;
    }

    private List<Map<String, Object>> parseSurefireXml(Path path, String xml) {
        List<Map<String, Object>> failures = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return failures;
        }
        Matcher testcase = Pattern.compile("<testcase\\s+([^>]*)>(.*?)</testcase>", Pattern.DOTALL).matcher(xml);
        while (testcase.find() && failures.size() < 20) {
            String attrs = testcase.group(1);
            String body = testcase.group(2);
            if (!body.contains("<failure") && !body.contains("<error")) {
                continue;
            }
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("reportFile", path == null ? "" : path.toString());
            failure.put("testClass", xmlAttr(attrs, "classname"));
            failure.put("testMethod", xmlAttr(attrs, "name"));
            String failureTag = firstNonBlank(extractTag(body, "failure"), extractTag(body, "error"));
            failure.put("message", abbreviate(xmlAttr(failureTag, "message"), 400));
            String stack = stripXmlTags(failureTag);
            failure.put("stackTop", abbreviate(stack.lines().findFirst().orElse(""), 400));
            List<Map<String, Object>> frames = extractStackTraceFrames(stack);
            failure.put("stackTraceFrames", frames);
            if (!frames.isEmpty()) {
                failure.put("file", frames.get(0).getOrDefault("file", ""));
            }
            failures.add(failure);
        }
        return failures;
    }

    private String extractTag(String body, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + "\\s+([^>]*)>(.*?)</" + tag + ">", Pattern.DOTALL).matcher(body == null ? "" : body);
        if (matcher.find()) {
            return matcher.group(1) + "\n" + matcher.group(2);
        }
        return "";
    }

    private String xmlAttr(String attrs, String name) {
        Matcher matcher = Pattern.compile(name + "=\"([^\"]*)\"").matcher(attrs == null ? "" : attrs);
        return matcher.find() ? unescapeXml(matcher.group(1)) : "";
    }

    private String stripXmlTags(String text) {
        return unescapeXml((text == null ? "" : text).replaceAll("<[^>]+>", ""));
    }

    private String unescapeXml(String value) {
        return value == null ? "" : value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private void addTextFiles(List<String> files, String text) {
        Matcher matcher = JAVA_FILE_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            files.add(matcher.group(1).replace('\\', '/'));
        }
    }

    private List<String> extractMavenCommands(String text) {
        List<String> commands = new ArrayList<>();
        Matcher matcher = MAVEN_COMMAND_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            commands.add(abbreviate(matcher.group().trim(), 180));
        }
        return distinct(commands);
    }

    private Map<String, Object> extractRepairScope(Map<String, Object> rawOutput) {
        Object guard = rawOutput.get("patchScopeGuard");
        if (guard instanceof Map<?, ?> gm && gm.get("repairScope") instanceof Map<?, ?> rs) {
            return mapValue(rs);
        }
        Object scope = rawOutput.get("repairScope");
        if (scope instanceof Map<?, ?> map) {
            return mapValue(map);
        }
        return Map.of();
    }

    private Map<String, Object> mapValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private void addList(List<String> target, Object value) {
        if (value instanceof List<?> list) {
            list.stream().map(String::valueOf).filter(item -> !item.isBlank()).forEach(target::add);
        } else if (value != null && !String.valueOf(value).isBlank()) {
            target.add(String.valueOf(value));
        }
    }

    private List<String> distinct(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private List<Map<String, Object>> distinctMaps(List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Map<String, Object> value : values) {
            String key = JSON.toJSONString(value);
            if (!seen.contains(key)) {
                seen.add(key);
                result.add(value);
            }
        }
        return result;
    }

    private void addIfPresent(List<String> target, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.add(String.valueOf(value));
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String stringValue(Object value, String defaultValue) {
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength)) + "...";
    }
}
