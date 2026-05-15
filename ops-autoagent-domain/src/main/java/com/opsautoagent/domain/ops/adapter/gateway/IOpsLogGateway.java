package com.opsautoagent.domain.ops.adapter.gateway;

import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.LogEvidenceEntity;

public interface IOpsLogGateway {

    LogEvidenceEntity queryLogs(IncidentCommandEntity command);

}

