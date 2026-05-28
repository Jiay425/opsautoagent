package com.opsautoagent.domain.codeops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeOpsReflectionReport {

    private int round;
    private String failedSkill;
    private String failureType;
    private List<String> mustFix;
    private List<String> mustAvoid;
    private boolean recovered;
    private String recoveryStrategy;
}
