package com.opsautoagent.domain.ops.agent.review;

import com.opsautoagent.domain.ops.model.entity.EvidenceSemanticEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsEvidenceReviewResult {

    private String status;

    private Integer round;

    private Boolean sufficient;

    private Integer confidenceScore;

    private List<String> confirmedFacts;

    private List<String> weakEvidence;

    private List<String> missingEvidence;

    private List<String> requiredTools;

    private List<String> reportConstraints;

    private OpsEvidenceSufficiency sufficiency;

    private List<EvidenceSemanticEntity> evidenceSemantics;

    private String conclusionType;

    private String rootCause;

    private String rootCauseCategory;

    private Integer rootCauseConfidence;

    private String rootCauseRationale;

    private List<String> candidateRootCauses;

    private String rationale;

}

