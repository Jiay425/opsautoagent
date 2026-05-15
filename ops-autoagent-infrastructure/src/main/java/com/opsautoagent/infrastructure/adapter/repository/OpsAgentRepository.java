package com.opsautoagent.infrastructure.adapter.repository;

import com.opsautoagent.domain.ops.adapter.repository.IOpsAgentRepository;
import com.opsautoagent.infrastructure.dao.IOpsAgentReviewDao;
import com.opsautoagent.infrastructure.dao.IOpsIncidentStateDao;
import com.opsautoagent.infrastructure.dao.IOpsInvestigationPlanDao;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;

@Repository
public class OpsAgentRepository implements IOpsAgentRepository {

    @Resource
    private IOpsIncidentStateDao opsIncidentStateDao;

    @Resource
    private IOpsInvestigationPlanDao opsInvestigationPlanDao;

    @Resource
    private IOpsAgentReviewDao opsAgentReviewDao;

    @Override
    public void saveIncidentState(com.opsautoagent.domain.ops.agent.state.OpsIncidentState incidentState) {
        opsIncidentStateDao.insert(toPo(incidentState));
    }

    @Override
    public void updateIncidentState(com.opsautoagent.domain.ops.agent.state.OpsIncidentState incidentState) {
        opsIncidentStateDao.updateByDiagnosisId(toPo(incidentState));
    }

    @Override
    public com.opsautoagent.domain.ops.agent.state.OpsIncidentState queryIncidentStateByDiagnosisId(String diagnosisId) {
        return toEntity(opsIncidentStateDao.queryByDiagnosisId(diagnosisId));
    }

    @Override
    public void saveInvestigationPlan(com.opsautoagent.domain.ops.agent.plan.OpsInvestigationPlan investigationPlan) {
        opsInvestigationPlanDao.insert(toPo(investigationPlan));
    }

    @Override
    public com.opsautoagent.domain.ops.agent.plan.OpsInvestigationPlan queryLatestPlanByDiagnosisId(String diagnosisId) {
        return toEntity(opsInvestigationPlanDao.queryLatestByDiagnosisId(diagnosisId));
    }

    @Override
    public void saveAgentReview(com.opsautoagent.domain.ops.agent.review.OpsAgentReview agentReview) {
        opsAgentReviewDao.insert(toPo(agentReview));
    }

    private com.opsautoagent.infrastructure.dao.po.OpsIncidentState toPo(com.opsautoagent.domain.ops.agent.state.OpsIncidentState state) {
        if (state == null) {
            return null;
        }
        return com.opsautoagent.infrastructure.dao.po.OpsIncidentState.builder()
                .stateId(state.getStateId())
                .diagnosisId(state.getDiagnosisId())
                .sessionId(state.getSessionId())
                .eventId(state.getEventId())
                .serviceName(state.getServiceName())
                .severity(state.getSeverity())
                .alertRule(state.getAlertRule())
                .timeWindowJson(state.getTimeWindowJson())
                .currentRound(state.getCurrentRound())
                .maxRounds(state.getMaxRounds())
                .planJson(state.getPlanJson())
                .metricsEvidenceJson(state.getMetricsEvidenceJson())
                .logEvidenceJson(state.getLogEvidenceJson())
                .traceEvidenceJson(state.getTraceEvidenceJson())
                .runbookEvidenceJson(state.getRunbookEvidenceJson())
                .candidateRootCausesJson(state.getCandidateRootCausesJson())
                .missingEvidenceJson(state.getMissingEvidenceJson())
                .toolHistoryJson(state.getToolHistoryJson())
                .reviewStatus(state.getReviewStatus())
                .finalReport(state.getFinalReport())
                .status(state.getStatus())
                .errorMessage(state.getErrorMessage())
                .createTime(state.getCreateTime())
                .updateTime(state.getUpdateTime())
                .build();
    }

    private com.opsautoagent.domain.ops.agent.state.OpsIncidentState toEntity(com.opsautoagent.infrastructure.dao.po.OpsIncidentState po) {
        if (po == null) {
            return null;
        }
        return com.opsautoagent.domain.ops.agent.state.OpsIncidentState.builder()
                .stateId(po.getStateId())
                .diagnosisId(po.getDiagnosisId())
                .sessionId(po.getSessionId())
                .eventId(po.getEventId())
                .serviceName(po.getServiceName())
                .severity(po.getSeverity())
                .alertRule(po.getAlertRule())
                .timeWindowJson(po.getTimeWindowJson())
                .currentRound(po.getCurrentRound())
                .maxRounds(po.getMaxRounds())
                .planJson(po.getPlanJson())
                .metricsEvidenceJson(po.getMetricsEvidenceJson())
                .logEvidenceJson(po.getLogEvidenceJson())
                .traceEvidenceJson(po.getTraceEvidenceJson())
                .runbookEvidenceJson(po.getRunbookEvidenceJson())
                .candidateRootCausesJson(po.getCandidateRootCausesJson())
                .missingEvidenceJson(po.getMissingEvidenceJson())
                .toolHistoryJson(po.getToolHistoryJson())
                .reviewStatus(po.getReviewStatus())
                .finalReport(po.getFinalReport())
                .status(po.getStatus())
                .errorMessage(po.getErrorMessage())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }

    private com.opsautoagent.infrastructure.dao.po.OpsInvestigationPlan toPo(com.opsautoagent.domain.ops.agent.plan.OpsInvestigationPlan plan) {
        if (plan == null) {
            return null;
        }
        return com.opsautoagent.infrastructure.dao.po.OpsInvestigationPlan.builder()
                .planId(plan.getPlanId())
                .diagnosisId(plan.getDiagnosisId())
                .stateId(plan.getStateId())
                .round(plan.getRound())
                .alertType(plan.getAlertType())
                .hypothesesJson(plan.getHypothesesJson())
                .stepsJson(plan.getStepsJson())
                .requiredToolsJson(plan.getRequiredToolsJson())
                .expectedEvidenceJson(plan.getExpectedEvidenceJson())
                .riskLevel(plan.getRiskLevel())
                .budgetJson(plan.getBudgetJson())
                .planJson(plan.getPlanJson())
                .plannerType(plan.getPlannerType())
                .createTime(plan.getCreateTime())
                .updateTime(plan.getUpdateTime())
                .build();
    }

    private com.opsautoagent.domain.ops.agent.plan.OpsInvestigationPlan toEntity(com.opsautoagent.infrastructure.dao.po.OpsInvestigationPlan po) {
        if (po == null) {
            return null;
        }
        return com.opsautoagent.domain.ops.agent.plan.OpsInvestigationPlan.builder()
                .planId(po.getPlanId())
                .diagnosisId(po.getDiagnosisId())
                .stateId(po.getStateId())
                .round(po.getRound())
                .alertType(po.getAlertType())
                .hypothesesJson(po.getHypothesesJson())
                .stepsJson(po.getStepsJson())
                .requiredToolsJson(po.getRequiredToolsJson())
                .expectedEvidenceJson(po.getExpectedEvidenceJson())
                .riskLevel(po.getRiskLevel())
                .budgetJson(po.getBudgetJson())
                .planJson(po.getPlanJson())
                .plannerType(po.getPlannerType())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }

    private com.opsautoagent.infrastructure.dao.po.OpsAgentReview toPo(com.opsautoagent.domain.ops.agent.review.OpsAgentReview review) {
        if (review == null) {
            return null;
        }
        return com.opsautoagent.infrastructure.dao.po.OpsAgentReview.builder()
                .reviewId(review.getReviewId())
                .diagnosisId(review.getDiagnosisId())
                .stateId(review.getStateId())
                .planId(review.getPlanId())
                .round(review.getRound())
                .reviewStatus(review.getReviewStatus())
                .sufficient(review.getSufficient())
                .confidence(review.getConfidence())
                .confirmedFactsJson(review.getConfirmedFactsJson())
                .weakEvidenceJson(review.getWeakEvidenceJson())
                .missingEvidenceJson(review.getMissingEvidenceJson())
                .nextActionsJson(review.getNextActionsJson())
                .reportConstraintsJson(review.getReportConstraintsJson())
                .stopReason(review.getStopReason())
                .reviewerType(review.getReviewerType())
                .reviewJson(review.getReviewJson())
                .createTime(review.getCreateTime())
                .updateTime(review.getUpdateTime())
                .build();
    }

}

