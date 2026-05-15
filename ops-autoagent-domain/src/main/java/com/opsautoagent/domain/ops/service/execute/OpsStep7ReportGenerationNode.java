package com.opsautoagent.domain.ops.service.execute;

import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentInput;
import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentJsonSupport;
import com.opsautoagent.domain.ops.agent.chat.OpsChatAgentOutput;
import com.opsautoagent.domain.ops.agent.chat.OpsMultiChatAgentService;
import com.opsautoagent.domain.ops.agent.plan.OpsInvestigationPlan;
import com.opsautoagent.domain.ops.agent.review.OpsEvidenceReviewResult;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAnalyzeEventEntity;
import com.opsautoagent.domain.common.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OpsStep7ReportGenerationNode extends AbstractOpsAgentExecuteSupport {

    @Value("${ops.agent.chat.enabled:false}")
    private boolean chatAgentEnabled;

    @Value("${ops.agent.chat.required:false}")
    private boolean chatAgentRequired;

    @Resource
    private OpsMultiChatAgentService opsMultiChatAgentService;

    @Override
    protected String doApply(IncidentCommandEntity requestParameter,
                             DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        send(dynamicContext, OpsAnalyzeEventEntity.running("agent", "evidence_reviewer_agent", 7,
                "Evidence Reviewer Agent started. Auditing collected Prometheus, ELK, SkyWalking and runbook evidence before report generation.",
                requestParameter.getSessionId()));

        DiagnosisReportResult reportResult = null;
        if (chatAgentEnabled) {
            send(dynamicContext, OpsAnalyzeEventEntity.running("agent", "report_writer_chat_agent", 7,
                    "Report Writer Chat Agent feature flag is enabled. Trying independent ChatClient Agent before fallback.",
                    requestParameter.getSessionId()));
            reportResult = tryGenerateReportWithChatAgent(requestParameter, dynamicContext);
            if (reportResult == null) {
                send(dynamicContext, OpsAnalyzeEventEntity.running("agent", "report_writer_chat_agent", 7,
                        "Report Writer Chat Agent unavailable or invalid. Falling back to existing report generation path.",
                        requestParameter.getSessionId()));
                if (chatAgentRequired) {
                    throw new IllegalStateException("Report Writer Chat Agent is required but unavailable or returned invalid output.");
                }
            }
        }
        if (reportResult == null) {
            reportResult = generateDiagnosisReport(
                    requestParameter,
                    dynamicContext.getMetricEvidence(),
                    dynamicContext.getLogEvidence(),
                    dynamicContext.getTraceEvidence(),
                    dynamicContext.getRootCauseCandidates(),
                    dynamicContext.getRunbookMatches());
        }

        send(dynamicContext, OpsAnalyzeEventEntity.running("agent", "evidence_reviewer_agent", 7,
                buildEvidenceReviewerMessage(reportResult),
                requestParameter.getSessionId()));
        send(dynamicContext, OpsAnalyzeEventEntity.running("agent", "report_writer_agent", 7,
                buildReportWriterMessage(reportResult),
                requestParameter.getSessionId()));

        dynamicContext.setReport(reportResult.getReport());
        dynamicContext.setStatus("SUCCESS");
        workingMemoryService.updateReport(dynamicContext, reportResult.getReport(), "SUCCESS");

        send(dynamicContext, OpsAnalyzeEventEntity.running("report", "diagnosis_report", 7,
                reportResult.getReport(), requestParameter.getSessionId()));
        saveDiagnosisRecord(requestParameter.getDiagnosisId(), requestParameter, dynamicContext, "SUCCESS", null);
        send(dynamicContext, OpsAnalyzeEventEntity.running("review", "diagnosis_record", 7,
                "Diagnosis review record saved. diagnosisId=" + requestParameter.getDiagnosisId(),
                requestParameter.getSessionId()));
        send(dynamicContext, OpsAnalyzeEventEntity.completed("Ops incident diagnosis completed",
                requestParameter.getSessionId()));
        return "success";
    }

    private DiagnosisReportResult tryGenerateReportWithChatAgent(IncidentCommandEntity command,
                                                                 DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
        OpsChatAgentOutput output = null;
        try {
            workingMemoryService.refreshFromContext(command, dynamicContext);
            OpsInvestigationPlan plan = dynamicContext.getValue("ops_investigation_plan");
            OpsEvidenceReviewResult reviewResult = dynamicContext.getValue("ops_evidence_review_result");
            String historicalMemoryJson = historicalIncidentMemoryService.toPromptJson(
                    historicalIncidentMemoryService.querySimilar(command, dynamicContext, 3));
            OpsChatAgentInput input = OpsChatAgentInput.builder()
                    .diagnosisId(command.getDiagnosisId())
                    .sessionId(command.getSessionId())
                    .serviceName(command.getServiceName())
                    .objective("Write the final ops incident diagnosis report. Return JSON only.")
                    .incidentContext(JSON.toJSONString(command))
                    .workingMemoryJson(workingMemoryService.toPromptJson(dynamicContext.getWorkingMemory()))
                    .historicalMemoryJson(historicalMemoryJson)
                    .toolConstraintsJson(buildReportToolConstraintsJson())
                    .planJson(plan == null ? "" : plan.getPlanJson())
                    .evidenceJson(JSON.toJSONString(dynamicContext.getWorkingMemory()))
                    .reviewerResultJson(reviewResult == null ? "" : JSON.toJSONString(reviewResult))
                    .runbookJson(JSON.toJSONString(dynamicContext.getRunbookMatches()))
                    .constraints(List.of(
                            "Do not invent facts.",
                            "State missing evidence explicitly.",
                            "Use evidenceSemantics from EvidenceReviewerAgent to explain why each signal is a symptom, root-cause indicator, negative evidence, or context.",
                            "Distinguish ROOT_CAUSE_CONFIRMED, PROBABLE_ROOT_CAUSE, and INVESTIGATION_COMPLETE_ROOT_CAUSE_UNRESOLVED.",
                            "For PROBABLE_ROOT_CAUSE, present the likely root cause as a hypothesis with confidence and explain negative evidence.",
                            "Runbooks and skills can support suggestions but not confirmed facts."
                    ))
                    .build();
            output = opsMultiChatAgentService.writeReport(input);
            if (output == null || !output.isSuccess()) {
                saveToolCallLog(command, "llm_report_writer_chat_agent", "report-writer-agent",
                        JSON.toJSONString(input), output == null ? "" : output.getRawContent(),
                        null, output == null ? 0L : output.getCostMillis(), false,
                        output == null ? "Report Writer Chat Agent returned no output." : output.getErrorMessage());
                return null;
            }
            JSONObject json = OpsChatAgentJsonSupport.parseObject(output.getContent());
            String finalReportMarkdown = json.getString("finalReportMarkdown");
            if (OpsChatAgentJsonSupport.isBlank(finalReportMarkdown)) {
                saveToolCallLog(command, "llm_report_writer_chat_agent", "report-writer-agent",
                        JSON.toJSONString(input), output.getRawContent(),
                        null, output.getCostMillis(), false,
                        "Report Writer Chat Agent returned blank finalReportMarkdown.");
                return buildNormalizedChatAgentReport(command, dynamicContext, reviewResult, output,
                        "blank finalReportMarkdown");
            }
            saveToolCallLog(command, "llm_report_writer_chat_agent", "report-writer-agent",
                    JSON.toJSONString(input), output.getRawContent(),
                    null, output.getCostMillis(), true, null);
            String evidenceReview = reviewResult == null ? "Evidence review result unavailable to Report Writer Chat Agent." : JSON.toJSONString(reviewResult);
            String report = buildChatAgentRuntimeHeader(output) + "\n\n" + finalReportMarkdown;
            return DiagnosisReportResult.success(report, evidenceReview, true,
                    output.getCostMillis(), output.getCostMillis(),
                    null);
        } catch (Exception ignore) {
            if (output != null && output.isSuccess()) {
                OpsEvidenceReviewResult reviewResult = dynamicContext.getValue("ops_evidence_review_result");
                return buildNormalizedChatAgentReport(command, dynamicContext, reviewResult, output,
                        "Report Writer Chat Agent returned content, but JSON normalization failed.");
            }
            return null;
        }
    }

    private DiagnosisReportResult buildNormalizedChatAgentReport(IncidentCommandEntity command,
                                                                 DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                                                 OpsEvidenceReviewResult reviewResult,
                                                                 OpsChatAgentOutput output,
                                                                 String fallbackReason) {
        DiagnosisReportResult fallback = generateDiagnosisReport(
                command,
                dynamicContext.getMetricEvidence(),
                dynamicContext.getLogEvidence(),
                dynamicContext.getTraceEvidence(),
                dynamicContext.getRootCauseCandidates(),
                dynamicContext.getRunbookMatches());
        String evidenceReview = reviewResult == null ? fallback.getEvidenceReview() : JSON.toJSONString(reviewResult);
        String report = buildNormalizedChatAgentRuntimeHeader(output, fallbackReason) + "\n\n" + fallback.getReport();
        return DiagnosisReportResult.success(report, evidenceReview, true,
                output.getCostMillis(), output.getCostMillis(), fallbackReason);
    }

    private String buildChatAgentRuntimeHeader(OpsChatAgentOutput output) {
        return String.format("""
                ## AI Runtime
                - Evidence Reviewer: CHAT_AGENT_CONTEXT
                - Report Writer: CHAT_AGENT
                - Report Writer Cost: %d ms
                - Client Bean: %s
                - Resolution Source: %s
                - Fallback Reason: none
                """,
                output.getCostMillis() == null ? 0L : output.getCostMillis(),
                blankToDefault(output.getClientBeanName(), "unknown"),
                blankToDefault(output.getResolutionSource(), "unknown"));
    }

    private String buildReportToolConstraintsJson() {
        return JSON.toJSONString(java.util.Map.of(
                "supportedTools", List.of(),
                "reportOnly", true,
                "governance", List.of(
                        "Report Writer Agent must not request tools.",
                        "Report Writer Agent can only summarize current working memory, current evidence, Runbook context, and explicitly labeled historical memories.",
                        "Historical memories must not be written as confirmed facts for the current incident."
                )
        ));
    }

    private String buildNormalizedChatAgentRuntimeHeader(OpsChatAgentOutput output, String fallbackReason) {
        return String.format("""
                ## AI Runtime
                - Evidence Reviewer: CHAT_AGENT_CONTEXT
                - Report Writer: CHAT_AGENT_NORMALIZED
                - Report Writer Cost: %d ms
                - Client Bean: %s
                - Resolution Source: %s
                - Fallback Reason: %s
                """,
                output.getCostMillis() == null ? 0L : output.getCostMillis(),
                blankToDefault(output.getClientBeanName(), "unknown"),
                blankToDefault(output.getResolutionSource(), "unknown"),
                blankToDefault(fallbackReason, "normalized report structure"));
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    @Override
    public StrategyHandler<IncidentCommandEntity, DefaultOpsAgentExecuteStrategyFactory.DynamicContext, String> get(
            IncidentCommandEntity requestParameter,
            DefaultOpsAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

}


