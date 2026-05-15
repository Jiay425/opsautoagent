package com.opsautoagent.domain.ops.agent.governance;

import java.util.Locale;

public final class OpsToolProtocolResolver {

    private OpsToolProtocolResolver() {
    }

    public static String protocolOf(String toolName, String target) {
        String tool = lower(toolName);
        String text = lower(toolName + " " + target);
        if (tool.contains("prometheus")) {
            return "PROMETHEUS_HTTP";
        }
        if (tool.contains("skywalking")) {
            return "SKYWALKING_HTTP";
        }
        if (tool.contains("elk") || tool.contains("elasticsearch")) {
            return "ELASTICSEARCH_HTTP";
        }
        if (tool.contains("runbook")) {
            return "RUNBOOK_RAG";
        }
        if (tool.contains("llm") || tool.contains("chat_agent")) {
            return "LLM_CHAT_AGENT";
        }
        if (tool.contains("mcp") || text.contains("mcpid=")) {
            if (text.contains("elastic") || text.contains("search")) {
                return "ELASTICSEARCH_MCP";
            }
            return "MCP";
        }
        return "INTERNAL";
    }

    public static String logicalToolNameOf(String toolName, String target) {
        String tool = lower(toolName);
        String text = lower(toolName + " " + target);
        if (tool.contains("prometheus")) {
            return "query_prometheus";
        }
        if (tool.contains("skywalking")) {
            return "query_skywalking_trace";
        }
        if (tool.contains("elk") || tool.contains("elasticsearch")) {
            return "query_elasticsearch";
        }
        if (tool.contains("runbook")) {
            return "query_runbook";
        }
        if (tool.contains("mcp") && (text.contains("elastic") || text.contains("search"))) {
            return "query_elasticsearch";
        }
        return toolName;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

}

