package com.opsautoagent.domain.ops.agent.eval;

import com.opsautoagent.domain.ops.model.entity.EvidenceSignalEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsRunbookRagEvalCase {

    private String caseId;

    private String caseName;

    private String serviceName;

    private String problem;

    private List<String> expectedRunbookIds;

    private List<EvidenceSignalEntity> evidenceSignals;

}

