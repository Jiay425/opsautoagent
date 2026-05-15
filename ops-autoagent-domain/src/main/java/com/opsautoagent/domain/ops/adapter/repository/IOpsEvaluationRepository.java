package com.opsautoagent.domain.ops.adapter.repository;

import com.opsautoagent.domain.ops.agent.eval.OpsEvalCase;
import com.opsautoagent.domain.ops.agent.eval.OpsEvalMetric;
import com.opsautoagent.domain.ops.agent.eval.OpsEvalRun;

import java.util.List;

public interface IOpsEvaluationRepository {

    List<OpsEvalCase> queryEnabledCases();

    OpsEvalCase queryEnabledCaseByCaseId(String caseId);

    void saveRun(OpsEvalRun evalRun);

    void updateRun(OpsEvalRun evalRun);

    void saveMetric(OpsEvalMetric evalMetric);

}

