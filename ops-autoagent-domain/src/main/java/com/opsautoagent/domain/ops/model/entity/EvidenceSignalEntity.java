package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EvidenceSignalEntity {

    private String signalId;

    private String source;

    private String evidenceType;

    private String name;

    private String status;

    private String entity;

    private String severity;

    private String timeWindow;

    private String summary;

    private String rawEvidence;

}

