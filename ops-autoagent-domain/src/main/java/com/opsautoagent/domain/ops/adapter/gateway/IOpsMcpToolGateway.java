package com.opsautoagent.domain.ops.adapter.gateway;

import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsMcpToolResultEntity;

import java.util.Map;

public interface IOpsMcpToolGateway {

    OpsMcpToolResultEntity callTool(IncidentCommandEntity command, String mcpId, String toolName, Map<String, Object> args);

    OpsMcpToolResultEntity listTools(IncidentCommandEntity command, String mcpId);

}

