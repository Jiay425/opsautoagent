package com.opsautoagent.domain.ops.adapter.gateway;

import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.TraceEvidenceEntity;

public interface IOpsTraceGateway {

    TraceEvidenceEntity queryTrace(IncidentCommandEntity command);

}

