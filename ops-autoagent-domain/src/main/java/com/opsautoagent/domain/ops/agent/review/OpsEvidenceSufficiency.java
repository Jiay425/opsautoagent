package com.opsautoagent.domain.ops.agent.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsEvidenceSufficiency {

    private Boolean directEvidence;

    private Boolean multiSourceSupport;

    private Boolean sourceCoverage;

    private Boolean rootCauseSupport;

    private Boolean rootCauseSpecificEvidence;

    private Boolean negativeEvidenceConsidered;

    private Boolean collectableEvidenceGap;

    private Boolean temporalAlignment;

    private Boolean entityAlignment;

    private Boolean runbookSupport;

    private Boolean noContradiction;

    private List<String> missingCriticalEvidence;

    private List<String> contradictions;

    private String rationale;

}

