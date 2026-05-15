package com.opsautoagent.infrastructure.adapter.gateway.ops;

import com.opsautoagent.domain.agent.model.valobj.enums.AiAgentEnumVO;
import com.opsautoagent.domain.ops.adapter.gateway.IOpsMcpToolGateway;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsMcpToolResultEntity;
import com.alibaba.fastjson.JSON;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class OpsMcpToolGateway extends AbstractOpsHttpGateway implements IOpsMcpToolGateway {

    @Resource
    private ApplicationContext applicationContext;

    @Override
    public OpsMcpToolResultEntity callTool(IncidentCommandEntity command, String mcpId, String toolName, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        String target = "mcpId=" + mcpId + ", toolName=" + toolName;
        try {
            McpSyncClient client = getMcpClient(mcpId);
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, args));
            String content = contentToString(result.content());
            boolean toolError = Boolean.TRUE.equals(result.isError());
            saveToolCallLog("mcp", command.getSessionId(), command.getDiagnosisId(), target,
                    JSON.toJSONString(args), content, null, System.currentTimeMillis() - start,
                    !toolError, toolError ? "MCP tool returned isError=true" : null);
            return OpsMcpToolResultEntity.builder()
                    .mcpId(mcpId)
                    .toolName(toolName)
                    .success(!toolError)
                    .toolError(toolError)
                    .content(content)
                    .costMillis(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.warn("Ops MCP tool call failed. mcpId={}, toolName={}", mcpId, toolName, e);
            saveToolCallLog("mcp", command.getSessionId(), command.getDiagnosisId(), target,
                    JSON.toJSONString(args), null, null, System.currentTimeMillis() - start,
                    false, e.getMessage());
            return OpsMcpToolResultEntity.builder()
                    .mcpId(mcpId)
                    .toolName(toolName)
                    .success(false)
                    .toolError(false)
                    .errorMessage(e.getMessage())
                    .costMillis(System.currentTimeMillis() - start)
                    .build();
        }
    }

    @Override
    public OpsMcpToolResultEntity listTools(IncidentCommandEntity command, String mcpId) {
        long start = System.currentTimeMillis();
        try {
            McpSyncClient client = getMcpClient(mcpId);
            McpSchema.ListToolsResult result = client.listTools();
            String content = JSON.toJSONString(result);
            saveToolCallLog("mcp", command.getSessionId(), command.getDiagnosisId(),
                    "mcpId=" + mcpId + ", listTools", "{}", content, null,
                    System.currentTimeMillis() - start, true, null);
            return OpsMcpToolResultEntity.builder()
                    .mcpId(mcpId)
                    .toolName("listTools")
                    .success(true)
                    .content(content)
                    .costMillis(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.warn("Ops MCP list tools failed. mcpId={}", mcpId, e);
            saveToolCallLog("mcp", command.getSessionId(), command.getDiagnosisId(),
                    "mcpId=" + mcpId + ", listTools", "{}", null, null,
                    System.currentTimeMillis() - start, false, e.getMessage());
            return OpsMcpToolResultEntity.builder()
                    .mcpId(mcpId)
                    .toolName("listTools")
                    .success(false)
                    .errorMessage(e.getMessage())
                    .costMillis(System.currentTimeMillis() - start)
                    .build();
        }
    }

    private McpSyncClient getMcpClient(String mcpId) {
        return applicationContext.getBean(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId), McpSyncClient.class);
    }

    private String contentToString(List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.stream()
                .map(item -> {
                    if (item instanceof McpSchema.TextContent textContent) {
                        return textContent.text();
                    }
                    return JSON.toJSONString(item);
                })
                .collect(Collectors.joining("\n"));
    }

}

