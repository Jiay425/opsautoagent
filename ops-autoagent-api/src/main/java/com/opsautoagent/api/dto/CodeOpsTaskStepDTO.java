package com.opsautoagent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * One decision/execution step in a CodeOps task.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsTaskStepDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer stepNo;

    private String decision;

    private String selectedSkill;

    private String reason;

    private List<String> expectedEvidence;

    private String resultSummary;

    private String rawEvidenceJson;

    private String status;

}
