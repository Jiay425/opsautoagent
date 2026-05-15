package com.opsautoagent.domain.ops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsEvalCase {

    private Long id;

    private String caseId;

    private String caseName;

    private String serviceName;

    private String alertPayloadJson;

    private String problem;

    private String expectedRootCause;

    private String expectedEvidenceTypesJson;

    private String expectedToolsJson;

    private String goldenSummary;

    private String severity;

    private String tags;

    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}

