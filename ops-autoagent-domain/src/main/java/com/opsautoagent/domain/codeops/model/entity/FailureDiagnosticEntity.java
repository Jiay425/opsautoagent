package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FailureDiagnosticEntity {

    private Integer round;

    private String failedSkill;

    private String failureType;

    private List<String> failedFiles;

    private List<String> failedMethods;

    private List<String> failedCommands;

    private List<String> mustFix;

    private List<String> mustAvoid;

    private List<String> nextAttemptConstraints;

    private Map<String, Object> repairScope;

    private Map<String, Object> modelRouting;

    private String rawFailureSummary;

    private String patchHash;

    public Map<String, Object> toRawOutput() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("round", round == null ? 0 : round);
        output.put("failedSkill", failedSkill == null ? "" : failedSkill);
        output.put("failureType", failureType == null ? "UNKNOWN" : failureType);
        output.put("failedFiles", failedFiles == null ? List.of() : failedFiles);
        output.put("failedMethods", failedMethods == null ? List.of() : failedMethods);
        output.put("failedCommands", failedCommands == null ? List.of() : failedCommands);
        output.put("mustFix", mustFix == null ? List.of() : mustFix);
        output.put("mustAvoid", mustAvoid == null ? List.of() : mustAvoid);
        output.put("nextAttemptConstraints", nextAttemptConstraints == null ? List.of() : nextAttemptConstraints);
        output.put("repairScope", repairScope == null ? Map.of() : repairScope);
        output.put("modelRouting", modelRouting == null ? Map.of() : modelRouting);
        output.put("rawFailureSummary", rawFailureSummary == null ? "" : rawFailureSummary);
        if (patchHash != null && !patchHash.isBlank()) {
            output.put("patchHash", patchHash);
        }
        return output;
    }
}
