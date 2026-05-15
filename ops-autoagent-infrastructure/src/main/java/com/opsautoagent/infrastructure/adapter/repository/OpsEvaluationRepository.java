package com.opsautoagent.infrastructure.adapter.repository;

import com.opsautoagent.domain.ops.adapter.repository.IOpsEvaluationRepository;
import com.opsautoagent.domain.ops.agent.eval.OpsEvalMetric;
import com.opsautoagent.infrastructure.dao.IOpsEvalCaseDao;
import com.opsautoagent.infrastructure.dao.IOpsEvalMetricDao;
import com.opsautoagent.infrastructure.dao.IOpsEvalRunDao;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.List;

@Repository
public class OpsEvaluationRepository implements IOpsEvaluationRepository {

    @Resource
    private IOpsEvalCaseDao opsEvalCaseDao;

    @Resource
    private IOpsEvalRunDao opsEvalRunDao;

    @Resource
    private IOpsEvalMetricDao opsEvalMetricDao;

    @Override
    public List<com.opsautoagent.domain.ops.agent.eval.OpsEvalCase> queryEnabledCases() {
        return opsEvalCaseDao.queryEnabledCases().stream().map(this::toEntity).toList();
    }

    @Override
    public com.opsautoagent.domain.ops.agent.eval.OpsEvalCase queryEnabledCaseByCaseId(String caseId) {
        return toEntity(opsEvalCaseDao.queryEnabledCaseByCaseId(caseId));
    }

    @Override
    public void saveRun(com.opsautoagent.domain.ops.agent.eval.OpsEvalRun evalRun) {
        opsEvalRunDao.insert(toPo(evalRun));
    }

    @Override
    public void updateRun(com.opsautoagent.domain.ops.agent.eval.OpsEvalRun evalRun) {
        opsEvalRunDao.updateByRunId(toPo(evalRun));
    }

    @Override
    public void saveMetric(OpsEvalMetric evalMetric) {
        opsEvalMetricDao.insert(toPo(evalMetric));
    }

    private com.opsautoagent.domain.ops.agent.eval.OpsEvalCase toEntity(com.opsautoagent.infrastructure.dao.po.OpsEvalCase po) {
        if (po == null) {
            return null;
        }
        return com.opsautoagent.domain.ops.agent.eval.OpsEvalCase.builder()
                .id(po.getId())
                .caseId(po.getCaseId())
                .caseName(po.getCaseName())
                .serviceName(po.getServiceName())
                .alertPayloadJson(po.getAlertPayloadJson())
                .problem(po.getProblem())
                .expectedRootCause(po.getExpectedRootCause())
                .expectedEvidenceTypesJson(po.getExpectedEvidenceTypesJson())
                .expectedToolsJson(po.getExpectedToolsJson())
                .goldenSummary(po.getGoldenSummary())
                .severity(po.getSeverity())
                .tags(po.getTags())
                .enabled(po.getEnabled())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }

    private com.opsautoagent.infrastructure.dao.po.OpsEvalRun toPo(com.opsautoagent.domain.ops.agent.eval.OpsEvalRun entity) {
        if (entity == null) {
            return null;
        }
        return com.opsautoagent.infrastructure.dao.po.OpsEvalRun.builder()
                .id(entity.getId())
                .runId(entity.getRunId())
                .caseId(entity.getCaseId())
                .diagnosisId(entity.getDiagnosisId())
                .status(entity.getStatus())
                .top1RootCauseHit(entity.getTop1RootCauseHit())
                .top3RootCauseHit(entity.getTop3RootCauseHit())
                .requiredEvidenceCoverage(entity.getRequiredEvidenceCoverage())
                .unsupportedConclusionCount(entity.getUnsupportedConclusionCount())
                .toolCallCount(entity.getToolCallCount())
                .diagnosisLatencyMs(entity.getDiagnosisLatencyMs())
                .finalStatus(entity.getFinalStatus())
                .summaryJson(entity.getSummaryJson())
                .errorMessage(entity.getErrorMessage())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    private com.opsautoagent.infrastructure.dao.po.OpsEvalMetric toPo(OpsEvalMetric entity) {
        if (entity == null) {
            return null;
        }
        return com.opsautoagent.infrastructure.dao.po.OpsEvalMetric.builder()
                .id(entity.getId())
                .runId(entity.getRunId())
                .caseId(entity.getCaseId())
                .metricName(entity.getMetricName())
                .metricValue(entity.getMetricValue())
                .metricDetailJson(entity.getMetricDetailJson())
                .createTime(entity.getCreateTime())
                .build();
    }

}

