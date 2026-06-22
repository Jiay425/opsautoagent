package com.opsautoagent.domain.codeops.agent.hook;

import com.opsautoagent.domain.codeops.agent.repair.RepairObservationService;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.RepairObservationEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CodeOpsHookService {

    private final RepairObservationService repairObservationService;
    private final List<CodeOpsHookHandler> handlers;

    public CodeOpsHookService(RepairObservationService repairObservationService,
                              List<CodeOpsHookHandler> handlers) {
        this.repairObservationService = repairObservationService;
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public CodeOpsHookService(RepairObservationService repairObservationService) {
        this(repairObservationService, List.of());
    }

    public CodeOpsHookResult emit(EngineeringTaskEntity task,
                                  CodeOpsHookEvent event,
                                  String phase,
                                  String summary,
                                  Map<String, Object> payload) {
        if (task == null || event == null) {
            return CodeOpsHookResult.builder().allowed(true).build();
        }
        List<CodeOpsHookDecision> decisions = runHandlers(task, event, phase, payload);
        CodeOpsHookResult result = aggregate(decisions);
        Map<String, Object> output = new LinkedHashMap<>();
        if (payload != null) {
            output.putAll(payload);
        }
        output.put("hookEvent", event.name());
        output.put("hookResult", result.toRawOutput());
        repairObservationService.record(task, RepairObservationEntity.builder()
                .phase(phase == null || phase.isBlank() ? "HOOK" : phase)
                .source("hook")
                .action(event.name())
                .status(result.isAllowed() ? "OBSERVED" : (result.isRequiresApproval() ? "REQUIRES_APPROVAL" : "BLOCKED"))
                .success(result.isAllowed())
                .summary(summary == null ? "" : summary)
                .errorType(result.isAllowed() ? "" : (result.isRequiresApproval() ? "HOOK_APPROVAL_REQUIRED" : "HOOK_BLOCKED"))
                .errorMessage(result.isAllowed() ? "" : result.getReason())
                .output(output)
                .build());
        return result;
    }

    private List<CodeOpsHookDecision> runHandlers(EngineeringTaskEntity task,
                                                  CodeOpsHookEvent event,
                                                  String phase,
                                                  Map<String, Object> payload) {
        if (handlers.isEmpty()) {
            return List.of();
        }
        List<CodeOpsHookDecision> decisions = new ArrayList<>();
        for (CodeOpsHookHandler handler : handlers) {
            if (handler == null || !handler.supports(event, phase, payload)) {
                continue;
            }
            try {
                CodeOpsHookDecision decision = handler.handle(task, event, phase, payload);
                if (decision != null) {
                    decisions.add(decision);
                }
            } catch (Exception e) {
                decisions.add(CodeOpsHookDecision.deny(handler.name(),
                        "hook handler failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return decisions;
    }

    private CodeOpsHookResult aggregate(List<CodeOpsHookDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return CodeOpsHookResult.builder()
                    .allowed(true)
                    .requiresApproval(false)
                    .reason("")
                    .decisions(List.of())
                    .build();
        }
        for (CodeOpsHookDecision decision : decisions) {
            if (decision != null && decision.isRequiresApproval()) {
                return CodeOpsHookResult.builder()
                        .allowed(false)
                        .requiresApproval(true)
                        .reason(decision.getReason())
                        .decisions(decisions)
                        .build();
            }
        }
        for (CodeOpsHookDecision decision : decisions) {
            if (decision != null && !decision.isAllowed()) {
                return CodeOpsHookResult.builder()
                        .allowed(false)
                        .requiresApproval(false)
                        .reason(decision.getReason())
                        .decisions(decisions)
                        .build();
            }
        }
        return CodeOpsHookResult.builder()
                .allowed(true)
                .requiresApproval(false)
                .reason("")
                .decisions(decisions)
                .build();
    }
}
