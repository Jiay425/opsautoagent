package com.opsautoagent.domain.ops.agent.memory;

import com.opsautoagent.domain.ops.adapter.repository.IOpsIncidentMemoryRepository;
import com.opsautoagent.domain.ops.model.entity.DiagnosisRecordEntity;
import com.opsautoagent.domain.ops.model.entity.HistoricalIncidentMemoryEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.OpsAlertEventEntity;
import com.opsautoagent.domain.ops.service.execute.DefaultOpsAgentExecuteStrategyFactory;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class OpsHistoricalIncidentMemoryService {

    @Resource
    private IOpsIncidentMemoryRepository incidentMemoryRepository;

    public void createFromDiagnosisRecord(DiagnosisRecordEntity record,
                                          DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        if (record == null || isBlank(record.getDiagnosisId()) || !"SUCCESS".equals(record.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        HistoricalIncidentMemoryEntity memory = HistoricalIncidentMemoryEntity.builder()
                .memoryId("hist-" + UUID.randomUUID())
                .diagnosisId(record.getDiagnosisId())
                .serviceName(record.getServiceName())
                .alertRule(abbreviate(record.getProblem(), 500))
                .severity(resolveSeverity(context))
                .symptomSummary(abbreviate(record.getProblem(), 1000))
                .evidenceSummary(abbreviate(buildEvidenceSummary(record), 3000))
                .rootCauseCategory(resolveRootCauseCategory(context))
                .rootCauseSummary(abbreviate(resolveRootCauseSummary(record, context), 1500))
                .remediationSummary(abbreviate(record.getReport(), 2500))
                .confidence(resolveConfidence(context))
                .reviewStatus(resolveReviewStatus(context))
                .timeWindowJson(JSON.toJSONString(Map.of("startTime", value(record.getStartTime()), "endTime", value(record.getEndTime()))))
                .tags(buildTags(record, context))
                .similarityText(abbreviate(buildSimilarityText(record, context), 6000))
                .sourceRecordJson(abbreviate(JSON.toJSONString(record), 6000))
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            incidentMemoryRepository.saveHistoricalMemory(memory);
        } catch (Exception e) {
            log.warn("Save historical incident memory failed. diagnosisId={}", record.getDiagnosisId(), e);
        }
    }

    public List<HistoricalIncidentMemoryEntity> querySimilar(OpsAlertEventEntity alertEvent,
                                                             IncidentCommandEntity command,
                                                             int limit) {
        String serviceName = command == null ? null : command.getServiceName();
        String keyword = String.join(" ",
                value(command == null ? null : command.getProblem()),
                value(alertEvent == null ? null : alertEvent.getAlertRule()),
                value(alertEvent == null ? null : alertEvent.getLabelsJson()),
                value(alertEvent == null ? null : alertEvent.getAnnotationsJson()));
        try {
            return incidentMemoryRepository.querySimilarMemories(serviceName, normalizeSearchKeyword(keyword), Math.max(1, limit));
        } catch (Exception e) {
            log.warn("Query historical incident memory failed. serviceName={}", serviceName, e);
            return List.of();
        }
    }

    public List<HistoricalIncidentMemoryEntity> querySimilar(IncidentCommandEntity command,
                                                             DefaultOpsAgentExecuteStrategyFactory.DynamicContext context,
                                                             int limit) {
        String keyword = String.join(" ",
                value(command == null ? null : command.getProblem()),
                context == null || context.getWorkingMemory() == null ? "" : value(context.getWorkingMemory().getMetricEvidenceSummary()),
                context == null || context.getWorkingMemory() == null ? "" : value(context.getWorkingMemory().getLogEvidenceSummary()),
                context == null || context.getWorkingMemory() == null ? "" : value(context.getWorkingMemory().getTraceEvidenceSummary()));
        try {
            return incidentMemoryRepository.querySimilarMemories(command == null ? null : command.getServiceName(), normalizeSearchKeyword(keyword), Math.max(1, limit));
        } catch (Exception e) {
            log.warn("Query historical incident memory failed. serviceName={}", command == null ? "" : command.getServiceName(), e);
            return List.of();
        }
    }

    public String toPromptJson(List<HistoricalIncidentMemoryEntity> memories) {
        if (memories == null || memories.isEmpty()) {
            return "[]";
        }
        return JSON.toJSONString(memories.stream().map(this::toPromptMap).toList());
    }

    private Map<String, Object> toPromptMap(HistoricalIncidentMemoryEntity memory) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("memoryId", memory.getMemoryId());
        item.put("diagnosisId", memory.getDiagnosisId());
        item.put("serviceName", memory.getServiceName());
        item.put("alertRule", memory.getAlertRule());
        item.put("symptomSummary", memory.getSymptomSummary());
        item.put("evidenceSummary", memory.getEvidenceSummary());
        item.put("rootCauseCategory", memory.getRootCauseCategory());
        item.put("rootCauseSummary", memory.getRootCauseSummary());
        item.put("remediationSummary", memory.getRemediationSummary());
        item.put("confidence", memory.getConfidence());
        item.put("reviewStatus", memory.getReviewStatus());
        item.put("tags", memory.getTags());
        item.put("memoryType", "HISTORICAL_INCIDENT_MEMORY");
        item.put("constraint", "Historical incidents are prior experience only; they are not current direct evidence.");
        return item;
    }

    private String buildEvidenceSummary(DiagnosisRecordEntity record) {
        return String.join("\n",
                "metrics=" + value(record.getMetricEvidenceJson()),
                "logs=" + value(record.getLogEvidenceJson()),
                "traces=" + value(record.getTraceEvidenceJson()),
                "runbooks=" + value(record.getRunbookJson()));
    }

    private String normalizeSearchKeyword(String keyword) {
        String text = value(keyword).toLowerCase();
        if (text.contains("hikari") || text.contains("connection pool") || text.contains("jdbc")) {
            return "hikari";
        }
        if (text.contains("5xx") || text.contains("500") || text.contains("exception")) {
            return "5xx";
        }
        if (text.contains("full gc") || text.contains("gc") || text.contains("heap")) {
            return "gc";
        }
        if (text.contains("redis")) {
            return "redis";
        }
        if (text.contains("rpc") || text.contains("dubbo") || text.contains("downstream")) {
            return "rpc";
        }
        if (text.contains("mq") || text.contains("kafka") || text.contains("rocketmq") || text.contains("backlog")) {
            return "mq";
        }
        if (text.contains("timeout") || text.contains("timed out")) {
            return "timeout";
        }
        if (text.length() > 80) {
            return "";
        }
        return text;
    }

    private String buildSimilarityText(DiagnosisRecordEntity record,
                                       DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        return String.join("\n",
                value(record.getServiceName()),
                value(record.getProblem()),
                buildEvidenceSummary(record),
                value(record.getEvidenceChainJson()),
                value(record.getRunbookJson()),
                context == null || context.getWorkingMemory() == null ? "" : value(context.getWorkingMemory().getReviewerResultJson()),
                value(record.getReport()));
    }

    private String resolveSeverity(DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        if (context == null || context.getWorkingMemory() == null) {
            return "";
        }
        return value(context.getWorkingMemory().getSeverity());
    }

    private String resolveReviewStatus(DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        if (context == null || context.getWorkingMemory() == null) {
            return "";
        }
        return value(context.getWorkingMemory().getReviewerStatus());
    }

    private Integer resolveConfidence(DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        if (context == null || context.getRootCauseCandidates() == null || context.getRootCauseCandidates().isEmpty()
                || context.getRootCauseCandidates().get(0).getConfidence() == null) {
            return 0;
        }
        return context.getRootCauseCandidates().get(0).getConfidence();
    }

    private String resolveRootCauseCategory(DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        if (context == null || context.getRootCauseCandidates() == null || context.getRootCauseCandidates().isEmpty()) {
            return "";
        }
        return value(context.getRootCauseCandidates().get(0).getCategory());
    }

    private String resolveRootCauseSummary(DiagnosisRecordEntity record,
                                           DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        if (context != null && context.getRootCauseCandidates() != null && !context.getRootCauseCandidates().isEmpty()) {
            return context.getRootCauseCandidates().get(0).getCause() + "\n" + value(context.getRootCauseCandidates().get(0).getReasoning());
        }
        return value(record.getEvidenceChainJson());
    }

    private String buildTags(DiagnosisRecordEntity record,
                             DefaultOpsAgentExecuteStrategyFactory.DynamicContext context) {
        return String.join(",",
                value(record.getServiceName()),
                resolveRootCauseCategory(context),
                resolveReviewStatus(context));
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

}

