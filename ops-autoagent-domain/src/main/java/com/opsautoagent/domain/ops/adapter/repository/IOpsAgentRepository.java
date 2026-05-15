package com.opsautoagent.domain.ops.adapter.repository;

import com.opsautoagent.domain.ops.agent.plan.OpsInvestigationPlan;
import com.opsautoagent.domain.ops.agent.review.OpsAgentReview;
import com.opsautoagent.domain.ops.agent.state.OpsIncidentState;

public interface IOpsAgentRepository {

    void saveIncidentState(OpsIncidentState incidentState);

    void updateIncidentState(OpsIncidentState incidentState);

    OpsIncidentState queryIncidentStateByDiagnosisId(String diagnosisId);

    void saveInvestigationPlan(OpsInvestigationPlan investigationPlan);

    OpsInvestigationPlan queryLatestPlanByDiagnosisId(String diagnosisId);

    void saveAgentReview(OpsAgentReview agentReview);

}

