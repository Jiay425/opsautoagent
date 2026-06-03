package com.opsautoagent.domain.ops.agent.eval;

import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsRunbookRagEvalRun {

    private String runId;

    private String caseId;

    private String caseName;

    private String status;

    private List<String> expectedRunbookIds;

    private List<RunbookMatchEntity> retrievedRunbooks;

    private Integer top1Hit;

    private Integer top3Hit;

    private Integer top5Hit;

    private Integer rank;

    private BigDecimal reciprocalRank;

    private Long latencyMs;

    private String failureReason;

    private String errorMessage;

}

