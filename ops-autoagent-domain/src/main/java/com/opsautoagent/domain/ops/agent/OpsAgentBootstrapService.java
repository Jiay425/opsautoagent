package com.opsautoagent.domain.ops.agent;

import com.opsautoagent.domain.ops.adapter.repository.IOpsAgentRepository;
import com.opsautoagent.domain.ops.agent.plan.OpsAgentPlannerService;
import com.opsautoagent.domain.ops.agent.plan.OpsInvestigationPlan;
import com.opsautoagent.domain.ops.agent.state.OpsIncidentState;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.entity.OpsDiagnosisDispatchEntity;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OpsAgentBootstrapService {

    @Value("${ops.agent.enabled:true}")
    private boolean agentEnabled;

    @Value("${ops.agent.max-rounds:2}")
    private int maxRounds;

    @Resource
    private IOpsAgentRepository opsAgentRepository;

    @Resource
    private OpsAgentPlannerService opsAgentPlannerService;

    public void initialize(OpsAlertEventEntity alertEvent,
                           OpsDiagnosisDispatchEntity dispatch,
                           IncidentCommandEntity command) {
        if (!agentEnabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        OpsIncidentState state = OpsIncidentState.builder()
                .stateId("state-" + UUID.randomUUID())
                .diagnosisId(command.getDiagnosisId())
                .sessionId(command.getSessionId())
                .eventId(alertEvent.getEventId())
                .serviceName(alertEvent.getServiceName())
                .severity(alertEvent.getSeverity())
                .alertRule(alertEvent.getAlertRule())
                .timeWindowJson(buildTimeWindowJson(command))
                .currentRound(1)
                .maxRounds(Math.max(1, maxRounds))
                .toolHistoryJson("[]")
                .reviewStatus("NOT_REVIEWED")
                .status("INIT")
                .createTime(now)
                .updateTime(now)
                .build();
        opsAgentRepository.saveIncidentState(state);

        OpsInvestigationPlan plan = opsAgentPlannerService.plan(alertEvent, command, state);
        opsAgentRepository.saveInvestigationPlan(plan);

        opsAgentRepository.updateIncidentState(OpsIncidentState.builder()
                .diagnosisId(command.getDiagnosisId())
                .planJson(plan.getPlanJson())
                .status("PLANNED")
                .updateTime(LocalDateTime.now())
                .build());
    }

    private String buildTimeWindowJson(IncidentCommandEntity command) {
        Map<String, Object> timeWindow = new LinkedHashMap<>();
        timeWindow.put("startTime", command.getStartTime());
        timeWindow.put("endTime", command.getEndTime());
        return JSON.toJSONString(timeWindow);
    }

}

