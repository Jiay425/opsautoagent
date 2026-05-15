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
public class EvidenceSemanticEntity {

    private String signalId;

    private String source;

    private String signalName;

    private String semanticType;

    private String causalLevel;

    private String specificity;

    private Integer evidenceStrength;

    private List<String> supports;

    private List<String> weakens;

    private String reasoning;

}

