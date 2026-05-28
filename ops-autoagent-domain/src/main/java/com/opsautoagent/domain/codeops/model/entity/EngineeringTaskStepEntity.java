package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EngineeringTaskStepEntity {

    private Integer stepNo;

    private String decision;

    private String selectedSkill;

    private String reason;

    private List<String> expectedEvidence;

    private String resultSummary;

    private String rawEvidenceJson;

    private String status;

}
