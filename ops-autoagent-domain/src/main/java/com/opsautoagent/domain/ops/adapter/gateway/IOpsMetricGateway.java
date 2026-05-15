package com.opsautoagent.domain.ops.adapter.gateway;

import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.MetricEvidenceEntity;

public interface IOpsMetricGateway {

    MetricEvidenceEntity queryMetrics(IncidentCommandEntity command);

}

