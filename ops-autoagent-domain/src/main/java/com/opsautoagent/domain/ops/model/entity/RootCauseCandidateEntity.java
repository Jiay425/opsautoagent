package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RootCauseCandidateEntity {

    private String cause;

    private String category;

    /**
     * 0-100 confidence score for the candidate.
     */
    private Integer confidence;

    private String reasoning;

    private List<EvidenceItemEntity> evidences;

    private List<String> remediationSuggestions;

    /**
     * True means this item is an evidence-backed hypothesis, not a final conclusion.
     */
    private Boolean hypothesis;

    private String origin;

    private String matchedRunbookId;

    private List<String> missingEvidence;

    private List<String> contradictions;

    private List<String> supportingSignals;

}

