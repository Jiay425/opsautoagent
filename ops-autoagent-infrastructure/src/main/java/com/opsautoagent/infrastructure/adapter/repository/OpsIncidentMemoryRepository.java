package com.opsautoagent.infrastructure.adapter.repository;

import com.opsautoagent.domain.ops.adapter.repository.IOpsIncidentMemoryRepository;
import com.opsautoagent.domain.ops.model.entity.HistoricalIncidentMemoryEntity;
import com.opsautoagent.infrastructure.dao.IOpsHistoricalIncidentMemoryDao;
import com.opsautoagent.infrastructure.dao.po.OpsHistoricalIncidentMemory;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.List;

@Repository
public class OpsIncidentMemoryRepository implements IOpsIncidentMemoryRepository {

    @Resource
    private IOpsHistoricalIncidentMemoryDao historicalIncidentMemoryDao;

    @Override
    public void saveHistoricalMemory(HistoricalIncidentMemoryEntity memory) {
        historicalIncidentMemoryDao.insert(toPo(memory));
    }

    @Override
    public List<HistoricalIncidentMemoryEntity> querySimilarMemories(String serviceName, String keyword, int limit) {
        return historicalIncidentMemoryDao.querySimilar(serviceName, keyword, Math.max(1, limit))
                .stream()
                .map(this::toEntity)
                .toList();
    }

    private OpsHistoricalIncidentMemory toPo(HistoricalIncidentMemoryEntity entity) {
        return OpsHistoricalIncidentMemory.builder()
                .id(entity.getId())
                .memoryId(entity.getMemoryId())
                .diagnosisId(entity.getDiagnosisId())
                .serviceName(entity.getServiceName())
                .alertRule(entity.getAlertRule())
                .severity(entity.getSeverity())
                .symptomSummary(entity.getSymptomSummary())
                .evidenceSummary(entity.getEvidenceSummary())
                .rootCauseCategory(entity.getRootCauseCategory())
                .rootCauseSummary(entity.getRootCauseSummary())
                .remediationSummary(entity.getRemediationSummary())
                .confidence(entity.getConfidence())
                .reviewStatus(entity.getReviewStatus())
                .timeWindowJson(entity.getTimeWindowJson())
                .tags(entity.getTags())
                .similarityText(entity.getSimilarityText())
                .sourceRecordJson(entity.getSourceRecordJson())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    private HistoricalIncidentMemoryEntity toEntity(OpsHistoricalIncidentMemory po) {
        return HistoricalIncidentMemoryEntity.builder()
                .id(po.getId())
                .memoryId(po.getMemoryId())
                .diagnosisId(po.getDiagnosisId())
                .serviceName(po.getServiceName())
                .alertRule(po.getAlertRule())
                .severity(po.getSeverity())
                .symptomSummary(po.getSymptomSummary())
                .evidenceSummary(po.getEvidenceSummary())
                .rootCauseCategory(po.getRootCauseCategory())
                .rootCauseSummary(po.getRootCauseSummary())
                .remediationSummary(po.getRemediationSummary())
                .confidence(po.getConfidence())
                .reviewStatus(po.getReviewStatus())
                .timeWindowJson(po.getTimeWindowJson())
                .tags(po.getTags())
                .similarityText(po.getSimilarityText())
                .sourceRecordJson(po.getSourceRecordJson())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }

}

