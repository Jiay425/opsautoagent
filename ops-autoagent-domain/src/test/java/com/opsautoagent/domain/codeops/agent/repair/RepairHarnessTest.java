package com.opsautoagent.domain.codeops.agent.repair;

import com.opsautoagent.domain.codeops.agent.bugfix.CodeOpsBugFixAgentInput;
import com.opsautoagent.domain.codeops.agent.bugfix.CodeOpsBugFixPrompts;
import com.opsautoagent.domain.codeops.agent.hook.CodeOpsHookService;
import com.opsautoagent.domain.codeops.agent.hook.SourceWriteSafetyHookHandler;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopDecision;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopRequest;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopResult;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopStep;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopToolCall;
import com.opsautoagent.domain.codeops.agent.recovery.FailureDiagnosticParserService;
import com.opsautoagent.domain.codeops.agent.security.AgentPermissionPolicy;
import com.opsautoagent.domain.codeops.agent.task.BackgroundToolTaskService;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolGateway;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolRegistry;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolRequest;
import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolResult;
import com.opsautoagent.domain.codeops.agent.tool.ToolPermissionGate;
import com.opsautoagent.domain.codeops.agent.tool.ToolRuntimeService;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.FailureDiagnosticEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RepairHarnessTest {

    @TempDir
    Path tempDir;

    @Test
    void exactReplaceReturnsContextStaleWhenOldTextDoesNotMatch() throws Exception {
        Path source = writeSource("""
                package demo;

                class Example {
                    String value() {
                        return "current";
                    }
                }
                """);
        EngineeringToolResult result = registry().execute(exactReplaceRequest(tempDir,
                "src/main/java/demo/Example.java",
                "return \"stale\";",
                "return \"updated\";"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo("CONTEXT_STALE");
        assertThat(result.getOutput()).isInstanceOf(Map.class);
        Map<?, ?> output = (Map<?, ?>) result.getOutput();
        assertThat(output.get("failureReason")).isEqualTo("OLD_TEXT_NOT_FOUND");
        assertThat(output.get("currentSnippet")).isNotNull();
    }

    @Test
    void exactReplaceReturnsMultipleMatchesWhenOldTextIsNotUnique() throws Exception {
        Path source = writeSource("""
                package demo;

                class Example {
                    String first() {
                        return "same";
                    }

                    String second() {
                        return "same";
                    }
                }
                """);
        EngineeringToolResult result = registry().execute(exactReplaceRequest(tempDir,
                "src/main/java/demo/Example.java",
                "return \"same\";",
                "return \"updated\";"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo("MULTIPLE_MATCHES");
        assertThat(result.getOutput()).isInstanceOf(Map.class);
        Map<?, ?> output = (Map<?, ?>) result.getOutput();
        assertThat(output.get("failureReason")).isEqualTo("MULTIPLE_MATCHES");
        assertThat(output.get("firstMatchContext")).isNotNull();
    }

    @Test
    void hookStrategyCanBlockUnsafeExactReplaceBeforeExecution() throws Exception {
        writeSource("""
                package demo;

                class Example {
                    String value() {
                        return "current";
                    }
                }
                """);
        EngineeringToolResult result = registryWithHookStrategy().execute(exactReplaceRequest(tempDir,
                "pom.xml",
                "current",
                "updated"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo("HOOK_BLOCKED");
        assertThat(result.getOutput()).isInstanceOf(Map.class);
        Map<?, ?> output = (Map<?, ?>) result.getOutput();
        assertThat(output.get("failureReason")).isEqualTo("HOOK_BLOCKED");
    }

    @Test
    void repairAgentLoopCanReadThenExactReplaceAndRecordAttempt() throws Exception {
        writeSource("""
                package demo;

                class Example {
                    String value() {
                        return "current";
                    }
                }
                """);
        EngineeringToolRegistry registry = registryWithHookStrategy();
        RepairObservationService observationService = new RepairObservationService();
        EngineeringTaskEntity task = EngineeringTaskEntity.builder()
                .taskId("task-loop")
                .taskType("BUG_FIX")
                .goal("replace current")
                .repository(tempDir.toString())
                .context(new LinkedHashMap<>())
                .build();
        AgentLoopResult result = new RepairAgentLoopService(registry, observationService)
                .run(RepairAgentLoopRequest.builder()
                        .task(task)
                        .repository(tempDir.toString())
                        .goal("replace current")
                        .inputFailureDiagnostic(Map.of("failureType", "TEST_ASSERTION_FAILED"))
                        .maxTurns(4)
                        .build(), readThenReplaceModel());

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(Files.readString(tempDir.resolve("src/main/java/demo/Example.java")))
                .contains("return \"updated\";");
        assertThat(task.getContext()).containsKey(RepairObservationService.PATCH_ATTEMPTS_KEY);
        List<?> attempts = (List<?>) task.getContext().get(RepairObservationService.PATCH_ATTEMPTS_KEY);
        assertThat(attempts).isNotEmpty();
        Map<?, ?> latest = (Map<?, ?>) attempts.get(attempts.size() - 1);
        assertThat(latest.get("editMethod")).isEqualTo("exactReplace");
        assertThat(latest.get("recovered")).isEqualTo(true);
    }

    @Test
    void compileFailureProducesStructuredCompileFailedDiagnostic() {
        FailureDiagnosticEntity diagnostic = parser().parse(1, "bug_fix", EngineeringSkillResultEntity.builder()
                .skillId("bug_fix")
                .status("FAILED")
                .summary("compile failed")
                .rawOutput(Map.of("compileGate", Map.of(
                        "requested", true,
                        "success", false,
                        "command", List.of("mvn", "-q", "-DskipTests", "compile"),
                        "output", """
                                [ERROR] /repo/src/main/java/demo/Example.java:[12,34] cannot find symbol
                                [ERROR] symbol:   method missing()
                                [ERROR] required: java.lang.String
                                [ERROR] found:    int
                                """)))
                .build());

        assertThat(diagnostic.getFailureType()).isEqualTo("COMPILE_FAILED");
        assertThat(diagnostic.getCompileErrors()).isNotEmpty();
        assertThat(diagnostic.getFailedCommands()).anyMatch(command -> command.contains("mvn"));
        assertThat(diagnostic.getMustFix()).anyMatch(item -> item.contains("Production code does not compile"));
    }

    @Test
    void testAssertionFailureProducesStructuredAssertionDiagnostic() {
        FailureDiagnosticEntity diagnostic = parser().parse(1, "test_verification", EngineeringSkillResultEntity.builder()
                .skillId("test_verification")
                .status("FAILED")
                .summary("test failed")
                .rawOutput(Map.of(
                        "testFailureType", "TEST_ASSERTION_FAILED",
                        "testExecutionResults", List.of("""
                                [ERROR] Failures:
                                [ERROR]   ExampleTest.shouldReturnOne:42 expected:<1> but was:<0>
                                    at demo.ExampleTest.shouldReturnOne(ExampleTest.java:42)
                                    at demo.Example.value(Example.java:12)
                                """)))
                .build());

        assertThat(diagnostic.getFailureType()).isEqualTo("TEST_ASSERTION_FAILED");
        assertThat(diagnostic.getTestAssertions()).isNotEmpty();
        assertThat(diagnostic.getStackTraceFrames()).isNotEmpty();
    }

    @Test
    void bugFixPromptCarriesFailureDiagnosticIntoReflectionRound() {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("failureType", "COMPILE_FAILED");
        diagnostic.put("mustFix", List.of("Fix missing method Example.missing"));
        diagnostic.put("mustAvoid", List.of("Do not edit unrelated files"));
        diagnostic.put("nextAttemptConstraints", List.of("Run compile before test"));
        diagnostic.put("repairScope", Map.of("scopeType", "FULL_FILE", "targetMethods", List.of("Example.value")));

        String prompt = CodeOpsBugFixPrompts.buildPrompt(CodeOpsBugFixAgentInput.builder()
                .taskId("task-1")
                .taskType("INCIDENT_TO_FIX")
                .goal("repair compile failure")
                .reflectionDiagnostics(List.of(diagnostic))
                .build());

        assertThat(prompt).contains("REFLECTION ROUND");
        assertThat(prompt).contains("Round 1 FAILED: COMPILE_FAILED");
        assertThat(prompt).contains("MUST FIX: Fix missing method Example.missing");
        assertThat(prompt).contains("CONSTRAINT: Run compile before test");
    }

    private FailureDiagnosticParserService parser() {
        return new FailureDiagnosticParserService();
    }

    private EngineeringToolRegistry registry() {
        AgentPermissionPolicy permissionPolicy = new AgentPermissionPolicy();
        ToolRuntimeService runtimeService = new ToolRuntimeService();
        EngineeringToolGateway gateway = new EngineeringToolGateway(permissionPolicy, runtimeService);
        RepairObservationService observationService = new RepairObservationService();
        return new EngineeringToolRegistry(gateway,
                new BackgroundToolTaskService(gateway),
                new ToolPermissionGate(permissionPolicy),
                observationService,
                new CodeOpsHookService(observationService));
    }

    private EngineeringToolRegistry registryWithHookStrategy() {
        AgentPermissionPolicy permissionPolicy = new AgentPermissionPolicy();
        ToolRuntimeService runtimeService = new ToolRuntimeService();
        EngineeringToolGateway gateway = new EngineeringToolGateway(permissionPolicy, runtimeService);
        RepairObservationService observationService = new RepairObservationService();
        return new EngineeringToolRegistry(gateway,
                new BackgroundToolTaskService(gateway),
                new ToolPermissionGate(permissionPolicy),
                observationService,
                new CodeOpsHookService(observationService, List.of(new SourceWriteSafetyHookHandler())));
    }

    private AgentLoopModelClient readThenReplaceModel() {
        AtomicInteger turn = new AtomicInteger();
        return new AgentLoopModelClient() {
            @Override
            public AgentLoopDecision next(AgentLoopRequest request, List<AgentLoopStep> previousSteps) {
                int current = turn.incrementAndGet();
                if (current == 1) {
                    return AgentLoopDecision.builder()
                            .toolCalls(List.of(AgentLoopToolCall.builder()
                                    .toolName("repo.read_file_snippet")
                                    .arguments(Map.of(
                                            "filePath", "src/main/java/demo/Example.java",
                                            "centerLine", 4,
                                            "radius", 4))
                                    .build()))
                            .build();
                }
                if (current == 2) {
                    return AgentLoopDecision.builder()
                            .toolCalls(List.of(AgentLoopToolCall.builder()
                                    .toolName("repo.exact_replace")
                                    .arguments(Map.of(
                                            "filePath", "src/main/java/demo/Example.java",
                                            "oldText", "return \"current\";",
                                            "newText", "return \"updated\";"))
                                    .build()))
                            .build();
                }
                return AgentLoopDecision.builder()
                        .finalAnswer("fixed")
                        .build();
            }
        };
    }

    private EngineeringToolRequest exactReplaceRequest(Path repository, String filePath, String oldText, String newText) {
        return EngineeringToolRequest.builder()
                .toolName("repo.exact_replace")
                .task(EngineeringTaskEntity.builder()
                        .taskId("task-1")
                        .taskType("BUG_FIX")
                        .repository(repository.toString())
                        .context(new LinkedHashMap<>())
                        .build())
                .arguments(Map.of(
                        "repository", repository.toString(),
                        "filePath", filePath,
                        "oldText", oldText,
                        "newText", newText))
                .build();
    }

    private Path writeSource(String content) throws Exception {
        Path file = tempDir.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
