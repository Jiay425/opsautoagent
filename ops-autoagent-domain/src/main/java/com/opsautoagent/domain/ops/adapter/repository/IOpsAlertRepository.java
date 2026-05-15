package com.opsautoagent.domain.ops.adapter.repository;

import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.entity.OpsDiagnosisDispatchEntity;

public interface IOpsAlertRepository {

    void saveAlertEvent(OpsAlertEventEntity alertEvent);

    void saveDiagnosisDispatch(OpsDiagnosisDispatchEntity dispatch);

    void updateDiagnosisDispatch(OpsDiagnosisDispatchEntity dispatch);

    OpsDiagnosisDispatchEntity queryLatestDispatchByDedupKey(String dedupKey);

    OpsDiagnosisDispatchEntity queryRunningDispatchByServiceName(String serviceName);

}

