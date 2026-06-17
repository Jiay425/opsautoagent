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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FailureDiagnosticParserService {

    private static final Pattern JAVA_FILE_PATTERN = Pattern.compile("([A-Za-z0-9_./\\\\-]+\\.java)(?::\\[(\\d+),\\d+]|:(\\d+))?");
    private static final Pattern MAVEN_COMMAND_PATTERN = Pattern.compile("mvn(?:\\.cmd)?\\s+[-A-Za-z0-9_:=./\\\\\\s]+");

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
            mustFix.addAll(extractCompileErrors(output));
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
                    || text.contains("but was:")
                    || text.contains("<<< failure!")
                    || text.contains("failures:");
            boolean hasTestCompileFailure = text.contains("testcompile")
                    || text.contains("compilation failure")
                    || text.contains("compilation error")
                    || text.contains("cannot find symbol")
                    || text.contains("does not exist");
            if ("TEST_ASSERTION_FAILED".equalsIgnoreCase(reportedTestFailureType) || hasAssertionFailure) {
                failureType = firstFailureType(failureType, "TEST_ASSERTION_FAILED");
                mustFix.add("Tests executed but assertions failed. Align implementation behavior with the failing assertion.");
                addAssertionRequirements(text, mustFix, nextAttemptConstraints);
                addTextFiles(failedFiles, text);
            } else if ("TEST_COMPILE_FAILED".equalsIgnoreCase(reportedTestFailureType) || hasTestCompileFailure) {
                failureType = firstFailureType(failureType, "TEST_COMPILE_FAILED");
                mustFix.add("Generated tests do not compile. Match visible production APIs and avoid missing dependencies.");
                addMissingApiRequirements(text, mustFix, nextAttemptConstraints);
                addTextFiles(failedFiles, text);
            } else if (text.contains("command timeout") || text.contains("timed out")) {
                failureType = firstFailureType(failureType, "TEST_TIMEOUT");
                mustFix.add("A Maven verification command timed out. Ensure tests have bounded execution time.");
                mustAvoid.add("Do not use unbounded waits or infinite loops in tests.");
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

    private List<String> extractCompileErrors(String output) {
        List<String> errors = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return errors;
        }
        for (String line : output.split("\\R")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("[error]") || lower.contains("compilation failure") || lower.contains("cannot find symbol")) {
                errors.add(abbreviate(line.trim(), 240));
            }
            if (errors.size() >= 12) {
                break;
            }
        }
        return errors;
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
