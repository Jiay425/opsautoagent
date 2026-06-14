package com.opsautoagent.domain.codeops.agent.tool;

import com.opsautoagent.domain.codeops.model.entity.CodeSnippetEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringToolDefinitionEntity;
import com.opsautoagent.domain.codeops.model.entity.RepoDiffContextEntity;
import com.opsautoagent.domain.codeops.agent.task.BackgroundToolTaskService;
import com.opsautoagent.domain.codeops.model.entity.BackgroundToolTaskEntity;
import org.springframework.stereotype.Service;

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
    private final Map<String, EngineeringToolDefinitionEntity> definitions = new LinkedHashMap<>();
    private final Map<String, EngineeringToolHandler> handlers = new LinkedHashMap<>();

    public EngineeringToolRegistry(EngineeringToolGateway gateway,
                                   BackgroundToolTaskService backgroundToolTaskService,
                                   ToolPermissionGate permissionGate) {
        this.gateway = gateway;
        this.backgroundToolTaskService = backgroundToolTaskService;
        this.permissionGate = permissionGate;
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
        ToolPermissionDecision decision = permissionGate.decide(request, find(request.getToolName()));
        if (decision.isRequiresApproval()) {
            return EngineeringToolResult.requiresApproval(request.getToolName(), decision.getReason(), decision.getPolicy());
        }
        if (!decision.isAllowed()) {
            return EngineeringToolResult.denied(request.getToolName(), decision.getReason());
        }
        EngineeringToolHandler handler = handlers.get(request.getToolName());
        if (handler == null) {
            return EngineeringToolResult.denied(request.getToolName(), "Tool is registered but has no handler");
        }
        try {
            return handler.execute(request);
        } catch (Exception e) {
            return EngineeringToolResult.failed(request.getToolName(), e);
        }
    }

    private void registerGatewayHandlers() {
        handlers.put("repo.create_snapshot", this::createSnapshot);
        handlers.put("repo.search_text", this::searchText);
        handlers.put("repo.read_file_snippet", this::readFileSnippet);
        handlers.put("repo.git_diff", this::gitDiff);
        handlers.put("repo.maven", this::maven);
        handlers.put("repo.maven_background", this::mavenBackground);
        handlers.put("task.background_status", this::backgroundStatus);
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

}
