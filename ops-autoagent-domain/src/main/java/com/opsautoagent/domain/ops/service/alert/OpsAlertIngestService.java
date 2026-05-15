package com.opsautoagent.domain.ops.service.alert;

import com.opsautoagent.api.dto.OpsAlertWebhookRequestDTO;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.valobj.alert.OpsAlertIngestResult;
import com.opsautoagent.domain.ops.service.dispatch.OpsDiagnosisDispatchService;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

@Service
public class OpsAlertIngestService {

    @Resource
    private OpsAlertNormalizeService opsAlertNormalizeService;

    @Resource
    private OpsDiagnosisDispatchService opsDiagnosisDispatchService;

    public OpsAlertIngestResult ingestAlertmanager(OpsAlertWebhookRequestDTO request) {
        List<OpsAlertEventEntity> alertEvents = opsAlertNormalizeService.normalizeAlertmanager(request);
        int accepted = 0;
        int skipped = 0;
        for (OpsAlertEventEntity alertEvent : alertEvents) {
            boolean dispatched = opsDiagnosisDispatchService.acceptAlertEvent(alertEvent);
            if (dispatched) {
                accepted++;
            } else {
                skipped++;
            }
        }
        return OpsAlertIngestResult.builder()
                .totalAlerts(alertEvents.size())
                .acceptedCount(accepted)
                .skippedCount(skipped)
                .build();
    }

}

