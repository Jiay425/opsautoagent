package com.opsautoagent.domain.codeops.agent.skill;

import com.alibaba.fastjson.JSON;
import com.opsautoagent.domain.codeops.agent.evidence.IncidentEvidenceExtractor;
import com.opsautoagent.domain.codeops.agent.fixture.IncidentFixtureEvidence;
import com.opsautoagent.domain.codeops.agent.fixture.IncidentFixtureEvidenceService;
import com.opsautoagent.domain.codeops.model.entity.OpsDiagnosisSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillResultEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.ops.adapter.repository.IOpsIncidentRepository;
import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.service.OpsIncidentExecuteStrategy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OpsDiagnosisEngineeringSkill implements EngineeringSkill {

    public static final String SKILL_ID = "ops_diagnosis";

    private static final Pattern SERVICE_PATTERN = Pattern.compile("([a-zA-Z0-9_-]+(?:-service|-app|-gateway))");

    private final OpsIncidentExecuteStrategy opsIncidentExecuteStrategy;

    private final IOpsIncidentRepository opsIncidentRepository;

    private final IncidentFixtureEvidenceService fixtureEvidenceService;

    private final IncidentEvidenceExtractor evidenceExtractor;

    public OpsDiagnosisEngineeringSkill(OpsIncidentExecuteStrategy opsIncidentExecuteStrategy,
                                        IOpsIncidentRepository opsIncidentRepository,
                                        IncidentFixtureEvidenceService fixtureEvidenceService,
                                        IncidentEvidenceExtractor evidenceExtractor) {
        this.opsIncidentExecuteStrategy = opsIncidentExecuteStrategy;
        this.opsIncidentRepository = opsIncidentRepository;
        this.fixtureEvidenceService = fixtureEvidenceService;
        this.evidenceExtractor = evidenceExtractor;
    }

    @Override
    public EngineeringSkillEntity metadata() {
        return EngineeringSkillEntity.builder()
                .skillId(SKILL_ID)
                .name("Ops Diagnosis Skill")
                .description("Reuse AutoAgent diagnosis capability to collect metric, log, trace and runbook evidence.")
                .supportedTaskTypes(List.of("INCIDENT_TO_FIX"))
                .requiredTools(List.of("ops.query_prometheus", "ops.search_logs", "ops.query_trace", "knowledge.search"))
                .riskLevel("READ_ONLY")
                .build();
    }

    @Override
    public EngineeringSkillResultEntity execute(EngineeringTaskEntity task) {
        IncidentCommandEntity command = buildCommand(task);
        OpsDiagnosisSkillResultEntity skillResult;
        String fixtureCase = task.getContext() == null ? null : stringValue(task.getContext().get("fixtureCase"));
        if (!isBlank(fixtureCase)) {
            skillResult = toFixtureSkillResult(command, fixtureEvidenceService.load(fixtureCase));
        } else if (hasAlertmanagerEvidence(task)) {
            skillResult = toAlertmanagerSkillResult(command, task);
        } else {
            try {
                opsIncidentExecuteStrategy.execute(command, null);
                DiagnosisRecordEntity record = opsIncidentRepository.queryDiagnosisRecord(command.getDiagnosisId());
                skillResult = toSkillResult(command, record, null);
            } catch (Exception e) {
                skillResult = toSkillResult(command, null, e);
            }
        }
        return EngineeringSkillResultEntity.builder()
                .skillId(SKILL_ID)
                .status(skillResult.getErrorMessage() == null ? "SUCCESS" : "FAILED")
                .summary(buildSummary(skillResult))
                .evidence(buildEvidence(skillResult))
                .nextActions(List.of("Planner Agent 将证据线索传给 Code Repair Agent", "Code Repair Agent 基于 stacktrace/Trace/源码定位并修复", "Reviewer Agent 审查修复是否消除 5xx"))
                .rawOutput(buildRawOutput(command, skillResult))
                .build();
    }

    private IncidentCommandEntity buildCommand(EngineeringTaskEntity task) {
        Map<String, Object> context = task.getContext() == null ? Map.of() : task.getContext();
        String serviceName = firstNonBlank(
                stringValue(context.get("serviceName")),
                stringValue(context.get("service")),
                extractServiceName(task.getGoal()),
                "unknown-service");
        String traceId = firstNonBlank(stringValue(context.get("traceId")), stringValue(context.get("trace")));
        String startTime = firstNonBlank(stringValue(context.get("startTime")), defaultStartTime());
        String endTime = firstNonBlank(stringValue(context.get("endTime")), defaultEndTime());
        return IncidentCommandEntity.builder()
                .serviceName(serviceName)
                .startTime(startTime)
                .endTime(endTime)
                .problem(task.getGoal())
                .traceId(traceId)
                .maxStep(6)
                .sessionId("codeops-" + UUID.randomUUID())
                .diagnosisId("codeops-diagnosis-" + UUID.randomUUID())
                .build();
    }

    private OpsDiagnosisSkillResultEntity toSkillResult(IncidentCommandEntity command,
                                                        DiagnosisRecordEntity record,
                                                        Exception error) {
        if (error != null) {
            return OpsDiagnosisSkillResultEntity.builder()
                    .diagnosisId(command.getDiagnosisId())
                    .sessionId(command.getSessionId())
                    .serviceName(command.getServiceName())
                    .timeWindow(command.getStartTime() + " ~ " + command.getEndTime())
                    .traceId(command.getTraceId())
                    .status("FAILED")
                    .reportSummary("")
                    .codeHints(List.of())
                    .evidenceSources(List.of())
                    .evidenceDetails(Map.of())
                    .errorMessage(error.getMessage())
                    .build();
        }
        List<String> evidenceSources = new ArrayList<>();
        if (!isBlank(record == null ? null : record.getMetricEvidenceJson())) {
            evidenceSources.add("Prometheus metrics");
        }
        if (!isBlank(record == null ? null : record.getLogEvidenceJson())) {
            evidenceSources.add("Elasticsearch logs");
        }
        if (!isBlank(record == null ? null : record.getTraceEvidenceJson())) {
            evidenceSources.add("SkyWalking traces");
        }
        if (!isBlank(record == null ? null : record.getRunbookJson())) {
            evidenceSources.add("Runbook RAG");
        }
        String allEvidenceText = String.join("\n",
                value(record == null ? null : record.getLogEvidenceJson()),
                value(record == null ? null : record.getTraceEvidenceJson()),
                value(record == null ? null : record.getEvidenceChainJson()),
                value(record == null ? null : record.getReport()));
        return OpsDiagnosisSkillResultEntity.builder()
                .diagnosisId(command.getDiagnosisId())
                .sessionId(command.getSessionId())
                .serviceName(command.getServiceName())
                .timeWindow(command.getStartTime() + " ~ " + command.getEndTime())
                .traceId(command.getTraceId())
                .status(record == null ? "NO_RECORD" : record.getStatus())
                .reportSummary(abbreviate(record == null ? "" : record.getReport(), 1200))
                .codeHints(evidenceExtractor.extractCodeHints(allEvidenceText))
                .evidenceSources(evidenceSources)
                .evidenceDetails(Map.of(
                        "metricEvidence", value(record == null ? null : record.getMetricEvidenceJson()),
                        "logEvidence", value(record == null ? null : record.getLogEvidenceJson()),
                        "traceEvidence", value(record == null ? null : record.getTraceEvidenceJson()),
                        "runbook", value(record == null ? null : record.getRunbookJson())
                ))
                .build();
    }

    private OpsDiagnosisSkillResultEntity toAlertmanagerSkillResult(IncidentCommandEntity command,
                                                                    EngineeringTaskEntity task) {
        Map<String, Object> context = task.getContext() == null ? Map.of() : task.getContext();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("alertmanagerPayload", context.get("alertmanagerPayload"));
        details.put("alertLabels", context.get("alertLabels") == null ? Map.of() : context.get("alertLabels"));
        details.put("alertAnnotations", context.get("alertAnnotations") == null ? Map.of() : context.get("alertAnnotations"));
        details.put("source", context.get("source"));
        String evidenceText = JSON.toJSONString(details) + "\n" + task.getGoal();
        return OpsDiagnosisSkillResultEntity.builder()
                .diagnosisId(command.getDiagnosisId())
                .sessionId(command.getSessionId())
                .serviceName(command.getServiceName())
                .timeWindow(command.getStartTime() + " ~ " + command.getEndTime())
                .traceId(command.getTraceId())
                .status("ALERTMANAGER_READY")
                .reportSummary(abbreviate(evidenceText, 1200))
                .codeHints(evidenceExtractor.extractCodeHints(evidenceText))
                .evidenceSources(List.of("Alertmanager webhook payload"))
                .evidenceDetails(details)
                .build();
    }

    private OpsDiagnosisSkillResultEntity toFixtureSkillResult(IncidentCommandEntity command,
                                                               IncidentFixtureEvidence fixtureEvidence) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fixtureCaseId", fixtureEvidence.getCaseId());
        details.put("fixtureBasePath", fixtureEvidence.getBasePath());
        details.put("alert", fixtureEvidence.getAlert() == null ? Map.of() : fixtureEvidence.getAlert());
        details.put("prometheus", fixtureEvidence.getPrometheus() == null ? Map.of() : fixtureEvidence.getPrometheus());
        details.put("logs", fixtureEvidence.getLogs() == null ? Map.of() : fixtureEvidence.getLogs());
        details.put("trace", fixtureEvidence.getTrace() == null ? Map.of() : fixtureEvidence.getTrace());
        return OpsDiagnosisSkillResultEntity.builder()
                .diagnosisId(command.getDiagnosisId())
                .sessionId(command.getSessionId())
                .serviceName(command.getServiceName())
                .timeWindow(command.getStartTime() + " ~ " + command.getEndTime())
                .traceId(command.getTraceId())
                .status(fixtureEvidence.isAvailable() ? "FIXTURE_READY" : "FIXTURE_FAILED")
                .reportSummary(abbreviate(fixtureEvidence.getReportSummary(), 1200))
                .codeHints(fixtureEvidence.getCodeHints() == null ? List.of() : fixtureEvidence.getCodeHints())
                .evidenceSources(fixtureEvidence.getEvidenceSources() == null ? List.of() : fixtureEvidence.getEvidenceSources())
                .evidenceDetails(details)
                .errorMessage(fixtureEvidence.getErrorMessage())
                .build();
    }

    private Map<String, Object> buildRawOutput(IncidentCommandEntity command, OpsDiagnosisSkillResultEntity skillResult) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("phase", "PHASE_4_OPS_DIAGNOSIS_SKILL");
        output.put("command", command);
        output.put("opsDiagnosis", skillResult);
        output.put("evidenceDetails", skillResult.getEvidenceDetails() == null ? Map.of() : skillResult.getEvidenceDetails());
        return output;
    }

    private List<String> buildEvidence(OpsDiagnosisSkillResultEntity result) {
        List<String> evidence = new ArrayList<>();
        evidence.add("服务：" + result.getServiceName());
        evidence.add("时间窗口：" + result.getTimeWindow());
        evidence.add("诊断 ID：" + result.getDiagnosisId());
        evidence.add("证据来源：" + (result.getEvidenceSources() == null || result.getEvidenceSources().isEmpty() ? "无" : String.join(", ", result.getEvidenceSources())));
        evidence.add("代码定位线索：" + (result.getCodeHints() == null || result.getCodeHints().isEmpty() ? "无" : String.join(", ", result.getCodeHints())));
        if (!isBlank(result.getReportSummary())) {
            evidence.add("诊断报告摘要：" + result.getReportSummary());
        }
        if (!isBlank(result.getErrorMessage())) {
            evidence.add("诊断错误：" + result.getErrorMessage());
        }
        return evidence;
    }

    private String buildSummary(OpsDiagnosisSkillResultEntity result) {
        if (!isBlank(result.getErrorMessage())) {
            return "OpsDiagnosisSkill 执行失败：service=" + result.getServiceName()
                    + "，diagnosisId=" + result.getDiagnosisId()
                    + "，error=" + result.getErrorMessage();
        }
        return "OpsDiagnosisSkill 已完成：service=" + result.getServiceName()
                + "，diagnosisId=" + result.getDiagnosisId()
                + "，evidenceSources=" + (result.getEvidenceSources() == null ? 0 : result.getEvidenceSources().size())
                + "，codeHints=" + (result.getCodeHints() == null ? 0 : result.getCodeHints().size());
    }

    private String extractServiceName(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String defaultStartTime() {
        return LocalDateTime.now().minusMinutes(30).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String defaultEndTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String ? (String) value : JSON.toJSONString(value);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasAlertmanagerEvidence(EngineeringTaskEntity task) {
        if (task.getContext() == null) {
            return false;
        }
        return !isBlank(stringValue(task.getContext().get("alertmanagerPayload")));
    }

}
