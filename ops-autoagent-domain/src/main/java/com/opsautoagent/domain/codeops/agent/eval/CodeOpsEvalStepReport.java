package com.opsautoagent.domain.codeops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsEvalStepReport {

    private int stepNo;
    private String decision;
    private String selectedSkill;
    private String status;
    private String summary;
    private String keyArtifact;
}
