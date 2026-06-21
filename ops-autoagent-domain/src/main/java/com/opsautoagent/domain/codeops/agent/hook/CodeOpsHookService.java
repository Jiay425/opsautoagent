package com.opsautoagent.domain.codeops.agent.hook;

import com.opsautoagent.domain.codeops.agent.repair.RepairObservationService;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.RepairObservationEntity;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CodeOpsHookService {

    private final RepairObservationService repairObservationService;

    public CodeOpsHookService(RepairObservationService repairObservationService) {
        this.repairObservationService = repairObservationService;
    }

    public void emit(EngineeringTaskEntity task,
                     CodeOpsHookEvent event,
                     String phase,
                     String summary,
                     Map<String, Object> payload) {
        if (task == null || event == null) {
            return;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        if (payload != null) {
            output.putAll(payload);
        }
        output.put("hookEvent", event.name());
        repairObservationService.record(task, RepairObservationEntity.builder()
                .phase(phase == null || phase.isBlank() ? "HOOK" : phase)
                .source("hook")
                .action(event.name())
                .status("OBSERVED")
                .success(true)
                .summary(summary == null ? "" : summary)
                .output(output)
                .build());
    }
}
