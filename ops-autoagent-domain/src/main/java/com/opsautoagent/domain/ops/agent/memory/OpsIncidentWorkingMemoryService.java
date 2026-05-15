package com.opsautoagent.domain.ops.agent.memory;

import com.opsautoagent.domain.ops.agent.plan.OpsInvestigationPlan;
import com.opsautoagent.domain.ops.agent.review.OpsEvidenceReviewResult;
import com.opsautoagent.domain.ops.agent.state.OpsIncidentState;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.LogEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.MetricEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.model.entity.TraceEvidenceEntity;
import com.opsautoagent.domain.ops.service.execute.DefaultOpsAgentExecuteStrategyFactory;
import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OpsIncidentWorkingMemoryService {

    public OpsIncidentWorkingMemory initialize(OpsAlertEventEntity alertEvent,
                                               IncidentCommandEntity command,
                                               OpsIncidentState state,
                                               OpsInvestigationPlan plan) {
        LocalDateTime now = LocalDateTime.now();
        OpsIncidentWorkingMemory memory = OpsIncidentWorkingMemory.builder()
                .memoryId("memory-" + UUID.randomUUID())
                .diagnosisId(command == null ? null : command.getDiagnosisId())
                .sessionId(command == null ? null : command.getSessionId())
                .serviceName(command == null ? null : command.getServiceName())
                .alertName(alertEvent == null ? null : alertEvent.getAlertRule())
                .severity(alertEvent == null ? null : alertEvent.getSeverity())
                .alertRule(alertEvent == null ? null : alertEvent.getAlertRule())
                .startTime(command == null || command.getStartTime() == null ? null : command.getStartTime().toString())
                .endTime(command == null || command.getEndTime() == null ? null : command.getEndTime().toString())
                .currentStatus(state == null ? "INIT" : state.getStatus())
                .toolHistoryJson(state == null ? "[]" : blankToDefault(state.getToolHistoryJson(), "[]"))
                .createTime(now)
                .updateTime(now)
                .build();
        return attachPlan(memory, plan);
    }

    public OpsIncidentWorkingMemory initialize(IncidentCommandEntity command,
                                               DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        if (context != null && context.getWorkingMemory() != null) {
            return refreshFromContext(command, context);
        }
        OpsInvestigationPlan plan = context == null ? null : context.getValue("ops_investigation_plan");
        OpsIncidentWorkingMemory memory = initialize(null, command, null, plan);
        if (context != null) {
            context.setWorkingMemory(memory);
        }
        return refreshFromContext(command, context);
    }

    public OpsIncidentWorkingMemory attachPlan(OpsIncidentWorkingMemory memory,
                                               OpsInvestigationPlan plan) {
        if (memory == null) {
            return null;
        }
        if (plan == null) {
            memory.setUpdateTime(LocalDateTime.now());
            return memory;
        }
        memory.setPlanId(plan.getPlanId());
        memory.setPlanRound(plan.getRound());
        memory.setPlannerType(plan.getPlannerType());
        memory.setAlertType(plan.getAlertType());
        memory.setPlanJson(plan.getPlanJson());
        memory.setHypothesesJson(plan.getHypothesesJson());
        memory.setRequiredToolsJson(plan.getRequiredToolsJson());
        memory.setExpectedEvidenceJson(plan.getExpectedEvidenceJson());
        memory.setUpdateTime(LocalDateTime.now());
        return memory;
    }

    public OpsIncidentWorkingMemory refreshFromContext(IncidentCommandEntity command,
                                                       DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        if (context == null) {
            return initialize(null, command, null, null);
        }
        OpsIncidentWorkingMemory memory = context.getWorkingMemory();
        if (memory == null) {
            memory = initialize(null, command, null, context.getValue("ops_investigation_plan"));
            context.setWorkingMemory(memory);
        }
        attachPlan(memory, context.getValue("ops_investigation_plan"));
        memory.setCurrentStatus(blankToDefault(context.getStatus(), memory.getCurrentStatus()));
        memory.setToolHistoryJson(JSON.toJSONString(context.getValue("ops_tool_history")));
        memory.setMetricEvidenceSummary(metricSummary(context.getMetricEvidence()));
        memory.setLogEvidenceSummary(logSummary(context.getLogEvidence()));
        memory.setTraceEvidenceSummary(traceSummary(context.getTraceEvidence()));
        memory.setEvidenceSignalsJson(toJson(context.getEvidenceSignals()));
        memory.setEvidenceSemanticsJson(toJson(context.getEvidenceSemantics()));
        memory.setRunbookMatchesJson(toJson(context.getRunbookMatches()));
        memory.setRootCauseCandidatesJson(toJson(context.getRootCauseCandidates()));
        OpsEvidenceReviewResult reviewResult = context.getValue("ops_evidence_review_result");
        if (reviewResult != null) {
            updateReview(memory, reviewResult);
        }
        memory.setFinalReportSummary(abbreviate(context.getReport(), 1200));
        memory.setUpdateTime(LocalDateTime.now());
        return memory;
    }

    public void updateReview(OpsIncidentWorkingMemory memory,
                             OpsEvidenceReviewResult reviewResult) {
        if (memory == null || reviewResult == null) {
            return;
        }
        memory.setReviewerStatus(reviewResult.getStatus());
        memory.setReviewerResultJson(toJson(reviewResult));
        memory.setMissingEvidenceJson(toJson(reviewResult.getMissingEvidence()));
        memory.setUpdateTime(LocalDateTime.now());
    }

    public void updateReport(DefaultOpsAgentExecuteStrategyFactory.DynamicContext context,
                             String report,
                             String status) {
        if (context == null) {
            return;
        }
        OpsIncidentWorkingMemory memory = context.getWorkingMemory();
        if (memory == null) {
            return;
        }
        memory.setFinalReportSummary(abbreviate(report, 1200));
        memory.setCurrentStatus(status);
        memory.setUpdateTime(LocalDateTime.now());
    }

    public String toPromptJson(OpsIncidentWorkingMemory memory) {
        if (memory == null) {
            return "{}";
        }
        Map<String, Object> promptMemory = new LinkedHashMap<>();
        promptMemory.put("memoryId", memory.getMemoryId());
        promptMemory.put("diagnosisId", memory.getDiagnosisId());
        promptMemory.put("sessionId", memory.getSessionId());
        promptMemory.put("serviceName", memory.getServiceName());
        promptMemory.put("alertName", memory.getAlertName());
        promptMemory.put("severity", memory.getSeverity());
        promptMemory.put("alertRule", memory.getAlertRule());
        Map<String, Object> timeWindow = new LinkedHashMap<>();
        timeWindow.put("startTime", value(memory.getStartTime()));
        timeWindow.put("endTime", value(memory.getEndTime()));
        promptMemory.put("timeWindow", timeWindow);
        promptMemory.put("currentStatus", memory.getCurrentStatus());
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planId", value(memory.getPlanId()));
        plan.put("round", memory.getPlanRound() == null ? 0 : memory.getPlanRound());
        plan.put("plannerType", value(memory.getPlannerType()));
        plan.put("alertType", value(memory.getAlertType()));
        plan.put("hypotheses", parseJson(memory.getHypothesesJson()));
        plan.put("requiredTools", parseJson(memory.getRequiredToolsJson()));
        plan.put("expectedEvidence", parseJson(memory.getExpectedEvidenceJson()));
        promptMemory.put("plan", plan);
        promptMemory.put("toolHistory", parseJson(memory.getToolHistoryJson()));
        Map<String, Object> evidenceSnapshot = new LinkedHashMap<>();
        evidenceSnapshot.put("metricEvidenceSummary", value(memory.getMetricEvidenceSummary()));
        evidenceSnapshot.put("logEvidenceSummary", value(memory.getLogEvidenceSummary()));
        evidenceSnapshot.put("traceEvidenceSummary", value(memory.getTraceEvidenceSummary()));
        evidenceSnapshot.put("evidenceSignals", parseJson(memory.getEvidenceSignalsJson()));
        evidenceSnapshot.put("evidenceSemantics", parseJson(memory.getEvidenceSemanticsJson()));
        evidenceSnapshot.put("runbookMatches", parseJson(memory.getRunbookMatchesJson()));
        promptMemory.put("evidenceSnapshot", evidenceSnapshot);
        Map<String, Object> reviewSnapshot = new LinkedHashMap<>();
        reviewSnapshot.put("reviewerStatus", value(memory.getReviewerStatus()));
        reviewSnapshot.put("missingEvidence", parseJson(memory.getMissingEvidenceJson()));
        reviewSnapshot.put("reviewerResult", parseJson(memory.getReviewerResultJson()));
        promptMemory.put("reviewSnapshot", reviewSnapshot);
        promptMemory.put("rootCauseCandidates", parseJson(memory.getRootCauseCandidatesJson()));
        promptMemory.put("finalReportSummary", value(memory.getFinalReportSummary()));
        promptMemory.put("updatedAt", memory.getUpdateTime() == null ? "" : memory.getUpdateTime().toString());
        return JSON.toJSONString(promptMemory);
    }

    private String metricSummary(MetricEvidenceEntity evidence) {
        if (evidence == null) {
            return "";
        }
        return abbreviate("available=" + evidence.isAvailable()
                + "; source=" + evidence.getSource()
                + "; summary=" + value(evidence.getSummary())
                + "; observations=" + JSON.toJSONString(evidence.getObservations()), 2000);
    }

    private String logSummary(LogEvidenceEntity evidence) {
        if (evidence == null) {
            return "";
        }
        return abbreviate("available=" + evidence.isAvailable()
                + "; source=" + evidence.getSource()
                + "; summary=" + value(evidence.getSummary())
                + "; errorSamples=" + JSON.toJSONString(evidence.getErrorSamples()), 2000);
    }

    private String traceSummary(TraceEvidenceEntity evidence) {
        if (evidence == null) {
            return "";
        }
        return abbreviate("available=" + evidence.isAvailable()
                + "; source=" + evidence.getSource()
                + "; summary=" + value(evidence.getSummary())
                + "; spans=" + JSON.toJSONString(evidence.getSpans()), 2000);
    }

    private Object parseJson(String json) {
        if (isBlank(json)) {
            return "";
        }
        try {
            return JSON.parse(json);
        } catch (Exception ignore) {
            return json;
        }
    }

    private String toJson(Object value) {
        return value == null ? "" : JSON.toJSONString(value);
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

}

