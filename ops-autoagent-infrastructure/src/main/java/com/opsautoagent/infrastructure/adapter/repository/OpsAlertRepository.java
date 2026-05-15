package com.opsautoagent.infrastructure.adapter.repository;

import com.opsautoagent.domain.ops.adapter.repository.IOpsAlertRepository;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.entity.OpsDiagnosisDispatchEntity;
import com.opsautoagent.infrastructure.dao.IOpsAlertEventDao;
import com.opsautoagent.infrastructure.dao.IOpsDiagnosisDispatchDao;
import com.opsautoagent.infrastructure.dao.po.OpsAlertEvent;
import com.opsautoagent.infrastructure.dao.po.OpsDiagnosisDispatch;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;

@Repository
public class OpsAlertRepository implements IOpsAlertRepository {

    @Resource
    private IOpsAlertEventDao opsAlertEventDao;

    @Resource
    private IOpsDiagnosisDispatchDao opsDiagnosisDispatchDao;

    @Override
    public void saveAlertEvent(OpsAlertEventEntity alertEvent) {
        opsAlertEventDao.insert(OpsAlertEvent.builder()
                .eventId(alertEvent.getEventId())
                .source(alertEvent.getSource())
                .serviceName(alertEvent.getServiceName())
                .alertRule(alertEvent.getAlertRule())
                .severity(alertEvent.getSeverity())
                .status(alertEvent.getStatus())
                .fingerprint(alertEvent.getFingerprint())
                .traceId(alertEvent.getTraceId())
                .startsAt(alertEvent.getStartsAt())
                .endsAt(alertEvent.getEndsAt())
                .labelsJson(alertEvent.getLabelsJson())
                .annotationsJson(alertEvent.getAnnotationsJson())
                .rawPayload(alertEvent.getRawPayload())
                .receivedTime(alertEvent.getReceivedTime())
                .createTime(alertEvent.getCreateTime())
                .build());
    }

    @Override
    public void saveDiagnosisDispatch(OpsDiagnosisDispatchEntity dispatch) {
        opsDiagnosisDispatchDao.insert(toPo(dispatch));
    }

    @Override
    public void updateDiagnosisDispatch(OpsDiagnosisDispatchEntity dispatch) {
        opsDiagnosisDispatchDao.updateByDispatchId(toPo(dispatch));
    }

    @Override
    public OpsDiagnosisDispatchEntity queryLatestDispatchByDedupKey(String dedupKey) {
        return toEntity(opsDiagnosisDispatchDao.queryLatestByDedupKey(dedupKey));
    }

    @Override
    public OpsDiagnosisDispatchEntity queryRunningDispatchByServiceName(String serviceName) {
        return toEntity(opsDiagnosisDispatchDao.queryRunningByServiceName(serviceName));
    }

    private OpsDiagnosisDispatch toPo(OpsDiagnosisDispatchEntity dispatch) {
        if (dispatch == null) {
            return null;
        }
        return OpsDiagnosisDispatch.builder()
                .dispatchId(dispatch.getDispatchId())
                .eventId(dispatch.getEventId())
                .diagnosisId(dispatch.getDiagnosisId())
                .serviceName(dispatch.getServiceName())
                .dedupKey(dispatch.getDedupKey())
                .dispatchStatus(dispatch.getDispatchStatus())
                .skipReason(dispatch.getSkipReason())
                .createTime(dispatch.getCreateTime())
                .startTime(dispatch.getStartTime())
                .endTime(dispatch.getEndTime())
                .updateTime(dispatch.getUpdateTime())
                .build();
    }

    private OpsDiagnosisDispatchEntity toEntity(OpsDiagnosisDispatch po) {
        if (po == null) {
            return null;
        }
        return OpsDiagnosisDispatchEntity.builder()
                .dispatchId(po.getDispatchId())
                .eventId(po.getEventId())
                .diagnosisId(po.getDiagnosisId())
                .serviceName(po.getServiceName())
                .dedupKey(po.getDedupKey())
                .dispatchStatus(po.getDispatchStatus())
                .skipReason(po.getSkipReason())
                .createTime(po.getCreateTime())
                .startTime(po.getStartTime())
                .endTime(po.getEndTime())
                .updateTime(po.getUpdateTime())
                .build();
    }

}

