package com.opsautoagent.domain.codeops.agent.tool;

import com.opsautoagent.domain.codeops.model.entity.CodeSnippetEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringToolDefinitionEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffContextEntity;
import com.opsautoagent.domain.codeops.agent.task.BackgroundToolTaskService;
import com.opsautoagent.domain.codeops.model.entity.BackgroundToolTaskEntity;
import com.opsautoagent.domain.codeops.agent.repair.RepairObservationService;
import com.opsautoagent.domain.codeops.agent.hook.CodeOpsHookEvent;
import com.opsautoagent.domain.codeops.agent.hook.CodeOpsHookResult;
import com.opsautoagent.domain.codeops.agent.hook.CodeOpsHookService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class EngineeringToolRegistry {

    private final EngineeringToolGateway gateway;
    private final BackgroundToolTaskService backgroundToolTaskService;
    private final ToolPermissionGate permissionGate;
    private final RepairObservationService repairObservationService;
    private final CodeOpsHookService hookService;
    private final Map<String, EngineeringToolDefinitionEntity> definitions = new LinkedHashMap<>();
    private final Map<String, EngineeringToolHandler> handlers = new LinkedHashMap<>();

    public EngineeringToolRegistry(EngineeringToolGateway gateway,
                                   BackgroundToolTaskService backgroundToolTaskService,
                                   ToolPermissionGate permissionGate,
                                   RepairObservationService repairObservationService,
                                   CodeOpsHookService hookService) {
        this.gateway = gateway;
        this.backgroundToolTaskService = backgroundToolTaskService;
        this.permissionGate = permissionGate;
        this.repairObservationService = repairObservationService;
        this.hookService = hookService;
        gateway.listTools().forEach(tool -> {
            tool.setArgumentSchema(argumentSchema(tool.getToolName()));
            definitions.put(tool.getToolName(), tool);
        });
        registerGatewayHandlers();
    }

    public List<EngineeringToolDefinitionEntity> listTools() {
        return definitions.values().stream()
                .filter(tool -> handlers.containsKey(tool.getToolName()))
                .toList();
    }

    public Optional<EngineeringToolDefinitionEntity> find(String toolName) {
        if (!handlers.containsKey(toolName)) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(toolName));
    }

    public ToolPermissionDecision previewPermission(EngineeringToolRequest request) {
        return permissionGate.decide(request, find(request == null ? "" : request.getToolName()));
    }

    public EngineeringToolResult execute(EngineeringToolRequest request) {
        if (request == null || request.getToolName() == null || request.getToolName().isBlank()) {
            return EngineeringToolResult.denied("", "Tool name is blank");
        }
        CodeOpsHookResult beforeHook = emitToolHook(request, CodeOpsHookEvent.BEFORE_TOOL_USE, "before tool permission");
        if (beforeHook != null && !beforeHook.isAllowed()) {
            EngineeringToolResult result = deniedByHook(request, beforeHook);
            recordObservation(request, null, result);
            emitToolHook(request, CodeOpsHookEvent.AFTER_TOOL_USE, result.getSummary());
            return result;
        }
        ToolPermissionDecision decision = permissionGate.decide(request, find(request.getToolName()));
        if (decision.isRequiresApproval()) {
            EngineeringToolResult result = EngineeringToolResult.requiresApproval(request.getToolName(), decision.getReason(), decision.getPolicy());
            recordObservation(request, decision, result);
            emitToolHook(request, CodeOpsHookEvent.AFTER_TOOL_USE, result.getSummary());
            return result;
        }
        if (!decision.isAllowed()) {
            EngineeringToolResult result = deniedByPermission(request, decision);
            recordObservation(request, decision, result);
            emitToolHook(request, CodeOpsHookEvent.AFTER_TOOL_USE, result.getSummary());
            return result;
        }
        EngineeringToolHandler handler = handlers.get(request.getToolName());
        if (handler == null) {
            EngineeringToolResult result = EngineeringToolResult.denied(request.getToolName(), "Tool is registered but has no handler");
            recordObservation(request, decision, result);
            emitToolHook(request, CodeOpsHookEvent.AFTER_TOOL_USE, result.getSummary());
            return result;
        }
        try {
            EngineeringToolResult result = handler.execute(request);
            recordObservation(request, decision, result);
            emitToolHook(request, CodeOpsHookEvent.AFTER_TOOL_USE, result.getSummary());
            return result;
        } catch (Exception e) {
            EngineeringToolResult result = EngineeringToolResult.failed(request.getToolName(), e);
            recordObservation(request, decision, result);
            emitToolHook(request, CodeOpsHookEvent.AFTER_TOOL_USE, result.getSummary());
            return result;
        }
    }

    private void registerGatewayHandlers() {
        handlers.put("repo.create_snapshot", this::createSnapshot);
        handlers.put("repo.search_text", this::searchText);
        handlers.put("repo.read_file_snippet", this::readFileSnippet);
        handlers.put("repo.git_diff", this::gitDiff);
        handlers.put("repo.maven", this::maven);
        handlers.put("repo.maven_background", this::mavenBackground);
        handlers.put("repo.exact_replace", this::exactReplace);
        handlers.put("task.background_status", this::backgroundStatus);
    }

    private void recordObservation(EngineeringToolRequest request,
                                   ToolPermissionDecision decision,
                                   EngineeringToolResult result) {
        if (request == null || request.getTask() == null || repairObservationService == null) {
            return;
        }
        repairObservationService.recordToolObservation(request.getTask(), "AGENT_TOOL", request, decision, result);
    }

    private EngineeringToolResult deniedByPermission(EngineeringToolRequest request, ToolPermissionDecision decision) {
        if (request != null && "repo.exact_replace".equals(request.getToolName())) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("failureReason", "WRITE_DENIED");
            output.put("filePath", request.stringArgument("filePath"));
            output.put("policy", decision == null || decision.getPolicy() == null ? Map.of() : decision.getPolicy());
            return EngineeringToolResult.builder()
                    .toolName(request.getToolName())
                    .success(false)
                    .status("DENIED")
                    .summary(decision == null ? "write denied" : decision.getReason())
                    .output(output)
                    .errorType("WRITE_DENIED")
                    .errorMessage(decision == null ? "write denied" : decision.getReason())
                    .build();
        }
        return EngineeringToolResult.denied(request == null ? "" : request.getToolName(),
                decision == null ? "" : decision.getReason());
    }

    private EngineeringToolResult deniedByHook(EngineeringToolRequest request, CodeOpsHookResult hookResult) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("failureReason", hookResult != null && hookResult.isRequiresApproval()
                ? "HOOK_APPROVAL_REQUIRED" : "HOOK_BLOCKED");
        output.put("hookResult", hookResult == null ? Map.of() : hookResult.toRawOutput());
        output.put("toolName", request == null ? "" : request.getToolName());
        return EngineeringToolResult.builder()
                .toolName(request == null ? "" : request.getToolName())
                .success(false)
                .status(hookResult != null && hookResult.isRequiresApproval() ? "REQUIRES_APPROVAL" : "DENIED")
                .summary(hookResult == null ? "blocked by hook" : hookResult.getReason())
                .output(output)
                .errorType(hookResult != null && hookResult.isRequiresApproval()
                        ? "HOOK_APPROVAL_REQUIRED" : "HOOK_BLOCKED")
                .errorMessage(hookResult == null ? "blocked by hook" : hookResult.getReason())
                .build();
    }

    private CodeOpsHookResult emitToolHook(EngineeringToolRequest request, CodeOpsHookEvent event, String summary) {
        if (request == null || request.getTask() == null || hookService == null) {
            return CodeOpsHookResult.builder().allowed(true).build();
        }
        return hookService.emit(request.getTask(), event, "AGENT_TOOL", summary, Map.of(
                "toolName", request.getToolName() == null ? "" : request.getToolName(),
                "arguments", request.getArguments() == null ? Map.of() : request.getArguments()
        ));
    }

    private EngineeringToolResult createSnapshot(EngineeringToolRequest request) {
        Map<String, String> snapshot = gateway.createRepositorySnapshot(repository(request));
        return EngineeringToolResult.success(request.getToolName(), "snapshotFiles=" + snapshot.size(), snapshot);
    }

    private EngineeringToolResult searchText(EngineeringToolRequest request) {
        List<String> matches = gateway.searchCode(repository(request), stringList(request.argument("queries")),
                request.intArgument("maxMatches", 50));
        return EngineeringToolResult.success(request.getToolName(), "matches=" + matches.size(), matches);
    }

    private EngineeringToolResult readFileSnippet(EngineeringToolRequest request) {
        int centerLine = resolveCenterLine(request);
        int radius = resolveRadius(request, centerLine);
        CodeSnippetEntity snippet = gateway.readFileSnippet(repository(request),
                request.stringArgument("filePath"),
                centerLine,
                radius);
        return EngineeringToolResult.success(request.getToolName(),
                "available=" + Boolean.TRUE.equals(snippet.getAvailable())
                        + ", lines=" + (snippet.getLines() == null ? 0 : snippet.getLines().size()),
                snippet);
    }

    private EngineeringToolResult gitDiff(EngineeringToolRequest request) {
        RepoDiffContextEntity diff = gateway.loadDiffContext(repository(request),
                request.stringArgument("changeRef"),
                mapArgument(request.argument("context")));
        return EngineeringToolResult.success(request.getToolName(),
                "diffAvailable=" + Boolean.TRUE.equals(diff.getDiffAvailable())
                        + ", changedFiles=" + (diff.getChangedFiles() == null ? 0 : diff.getChangedFiles().size()),
                diff);
    }

    private EngineeringToolResult maven(EngineeringToolRequest request) {
        EngineeringToolGateway.CommandResult result = gateway.runMavenCommand(repository(request),
                stringList(request.argument("args")),
                request.longArgument("timeoutMillis", 120_000L));
        return EngineeringToolResult.builder()
                .toolName(request.getToolName())
                .success(result.success())
                .status(result.success() ? "SUCCESS" : "FAILED")
                .summary("exitCode=" + result.exitCode() + ", costMillis=" + result.costMillis())
                .output(result)
                .errorType(result.success() ? "" : "COMMAND_FAILED")
                .errorMessage(result.success() ? "" : result.output())
                .build();
    }

    private EngineeringToolResult mavenBackground(EngineeringToolRequest request) {
        BackgroundToolTaskEntity backgroundTask = backgroundToolTaskService.startMavenAsync(request.getTask(),
                request.stringArgument("nodeId"),
                repository(request),
                stringList(request.argument("args")),
                request.longArgument("timeoutMillis", 120_000L));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("backgroundTaskId", backgroundTask.getBackgroundTaskId());
        output.put("status", backgroundTask.getStatus());
        output.put("toolName", backgroundTask.getToolName());
        output.put("requestSummary", backgroundTask.getRequestSummary());
        output.put("nodeId", backgroundTask.getNodeId());
        output.put("createTime", backgroundTask.getCreateTime());
        return EngineeringToolResult.builder()
                .toolName(request.getToolName())
                .success(true)
                .status("RUNNING")
                .summary("backgroundTaskId=" + backgroundTask.getBackgroundTaskId()
                        + ", status=" + backgroundTask.getStatus())
                .output(output)
                .metadata(Map.of("backgroundTaskId", backgroundTask.getBackgroundTaskId()))
                .build();
    }

    private EngineeringToolResult backgroundStatus(EngineeringToolRequest request) {
        String backgroundTaskId = request.stringArgument("backgroundTaskId");
        BackgroundToolTaskEntity backgroundTask = backgroundToolTaskService.find(backgroundTaskId);
        if (backgroundTask == null) {
            return EngineeringToolResult.denied(request.getToolName(), "Unknown backgroundTaskId: " + backgroundTaskId);
        }
        return EngineeringToolResult.success(request.getToolName(),
                "backgroundTaskId=" + backgroundTaskId + ", status=" + backgroundTask.getStatus(),
                backgroundTask);
    }

    private EngineeringToolResult exactReplace(EngineeringToolRequest request) {
        String repository = repository(request);
        String filePath = request.stringArgument("filePath");
        String oldText = normalizeLineEndings(request.stringArgument("oldText"));
        String newText = normalizeLineEndings(request.stringArgument("newText"));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("repository", repository);
        output.put("filePath", filePath);
        output.put("oldTextLength", oldText.length());
        output.put("newTextLength", newText.length());
        if (repository.isBlank() || filePath.isBlank() || oldText.isBlank()) {
            output.put("failureReason", "INVALID_ARGUMENT");
            return EngineeringToolResult.builder()
                    .toolName(request.getToolName())
                    .success(false)
                    .status("FAILED")
                    .summary("repo.exact_replace invalid arguments")
                    .output(output)
                    .errorType("INVALID_ARGUMENT")
                    .errorMessage("repository, filePath and oldText are required")
                    .build();
        }
        Path repoRoot = Path.of(repository).toAbsolutePath().normalize();
        Path file = repoRoot.resolve(filePath).normalize();
        if (!file.startsWith(repoRoot) || !Files.exists(file) || !Files.isRegularFile(file)) {
            output.put("failureReason", "FILE_NOT_FOUND");
            return EngineeringToolResult.builder()
                    .toolName(request.getToolName())
                    .success(false)
                    .status("FAILED")
                    .summary("repo.exact_replace file not found")
                    .output(output)
                    .errorType("FILE_NOT_FOUND")
                    .errorMessage(filePath + " not found or outside repository")
                    .build();
        }
        try {
            String current = normalizeLineEndings(Files.readString(file, StandardCharsets.UTF_8));
            int first = current.indexOf(oldText);
            if (first < 0) {
                output.put("failureReason", "OLD_TEXT_NOT_FOUND");
                output.put("contextStale", true);
                output.put("currentSnippet", contextForMismatch(current, oldText));
                return EngineeringToolResult.builder()
                        .toolName(request.getToolName())
                        .success(false)
                        .status("FAILED")
                        .summary("repo.exact_replace oldText not found")
                        .output(output)
                        .errorType("CONTEXT_STALE")
                        .errorMessage("oldText not found; re-read the current file before retry")
                        .build();
            }
            int second = current.indexOf(oldText, first + oldText.length());
            if (second >= 0) {
                output.put("failureReason", "MULTIPLE_MATCHES");
                output.put("firstMatchContext", contextAt(current, first));
                return EngineeringToolResult.builder()
                        .toolName(request.getToolName())
                        .success(false)
                        .status("FAILED")
                        .summary("repo.exact_replace multiple oldText matches")
                        .output(output)
                        .errorType("MULTIPLE_MATCHES")
                        .errorMessage("oldText matched multiple locations; include a larger unique block")
                        .build();
            }
            String updated = current.substring(0, first) + newText + current.substring(first + oldText.length());
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            output.put("failureReason", "");
            output.put("matchOffset", first);
            output.put("updated", true);
            output.put("updatedSnippet", contextAt(updated, first));
            return EngineeringToolResult.success(request.getToolName(),
                    "repo.exact_replace applied filePath=" + filePath,
                    output);
        } catch (IOException e) {
            output.put("failureReason", "IO_ERROR");
            return EngineeringToolResult.builder()
                    .toolName(request.getToolName())
                    .success(false)
                    .status("FAILED")
                    .summary("repo.exact_replace IO error")
                    .output(output)
                    .errorType("IO_ERROR")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private String repository(EngineeringToolRequest request) {
        String repository = request.stringArgument("repository");
        if (repository.isBlank() && request.getTask() != null) {
            repository = request.getTask().getRepository();
        }
        if (repository.isBlank() && request.getExecutionContext() != null) {
            repository = request.getExecutionContext().getRepository();
        }
        return repository;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(value));
    }

    private Map<String, Object> mapArgument(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private int resolveCenterLine(EngineeringToolRequest request) {
        int centerLine = request.intArgument("centerLine", 0);
        if (centerLine > 0) {
            return centerLine;
        }
        int startLine = request.intArgument("startLine", 0);
        int endLine = request.intArgument("endLine", 0);
        if (startLine > 0 && endLine >= startLine) {
            return Math.max(1, startLine + ((endLine - startLine) / 2));
        }
        return startLine > 0 ? startLine : 1;
    }

    private int resolveRadius(EngineeringToolRequest request, int centerLine) {
        int radius = request.intArgument("radius", 0);
        if (radius > 0) {
            return radius;
        }
        int startLine = request.intArgument("startLine", 0);
        int endLine = request.intArgument("endLine", 0);
        if (startLine > 0 && endLine >= startLine) {
            return Math.max(1, Math.max(centerLine - startLine, endLine - centerLine));
        }
        return 20;
    }

    private Map<String, Object> argumentSchema(String toolName) {
        return switch (toolName) {
            case "repo.create_snapshot" -> schema(Map.of(
                    "repository", "string, required, absolute or workspace-relative repository path"
            ));
            case "repo.search_text" -> schema(Map.of(
                    "repository", "string, required, absolute or workspace-relative repository path",
                    "queries", "string[], required, keywords or exact symbols to search",
                    "maxMatches", "integer, optional, default 50"
            ));
            case "repo.read_file_snippet" -> schema(Map.of(
                    "repository", "string, required, absolute or workspace-relative repository path",
                    "filePath", "string, required, repository-relative file path",
                    "centerLine", "integer, optional, preferred line center",
                    "radius", "integer, optional, number of lines around centerLine",
                    "startLine", "integer, optional, accepted compatibility alias",
                    "endLine", "integer, optional, accepted compatibility alias"
            ));
            case "repo.git_diff" -> schema(Map.of(
                    "repository", "string, required, absolute or workspace-relative repository path",
                    "changeRef", "string, optional, defaults to working_tree",
                    "context", "object, optional, may include repoBaselineSnapshot"
            ));
            case "repo.maven", "repo.maven_background" -> schema(Map.of(
                    "repository", "string, required, absolute or workspace-relative repository path",
                    "args", "string[], required, Maven arguments only, without leading mvn",
                    "timeoutMillis", "integer, optional, default 120000",
                    "nodeId", "string, optional, DAG node id for notification correlation"
            ));
            case "repo.exact_replace" -> schema(Map.of(
                    "repository", "string, required, absolute or workspace-relative repository path",
                    "filePath", "string, required, repository-relative file path",
                    "oldText", "string, required, exact current source block",
                    "newText", "string, required, replacement source block"
            ));
            case "task.background_status" -> schema(Map.of(
                    "backgroundTaskId", "string, required, id returned by repo.maven_background"
            ));
            default -> Map.of();
        };
    }

    private Map<String, Object> schema(Map<String, String> arguments) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("arguments", new LinkedHashMap<>(arguments));
        return schema;
    }

    private String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String contextForMismatch(String current, String oldText) {
        String firstLine = oldText == null ? "" : oldText.lines().findFirst().orElse("").trim();
        if (current == null || current.isBlank() || firstLine.isBlank()) {
            return abbreviate(current, 600);
        }
        int index = current.indexOf(firstLine);
        return index < 0 ? abbreviate(current, 600) : contextAt(current, index);
    }

    private String contextAt(String text, int index) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int safeIndex = Math.max(0, Math.min(index, text.length()));
        int start = Math.max(0, safeIndex - 300);
        int end = Math.min(text.length(), safeIndex + 300);
        return text.substring(start, end);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

}
