package com.opsautoagent.domain.codeops.agent.patch;

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
public class PatchScopeGuardResult {

    private boolean passed;

    private String failureType;

    private List<String> touchedFiles;

    private List<String> changedMethods;

    private List<String> violations;

    private Map<String, Object> repairScope;

    public Map<String, Object> toRawOutput() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("passed", passed);
        output.put("failureType", failureType == null ? "" : failureType);
        output.put("touchedFiles", touchedFiles == null ? List.of() : touchedFiles);
        output.put("changedMethods", changedMethods == null ? List.of() : changedMethods);
        output.put("violations", violations == null ? List.of() : violations);
        output.put("repairScope", repairScope == null ? Map.of() : repairScope);
        return output;
    }

    public static PatchScopeGuardResult passed(List<String> touchedFiles,
                                                List<String> changedMethods,
                                                Map<String, Object> repairScope) {
        return PatchScopeGuardResult.builder()
                .passed(true)
                .failureType("")
                .touchedFiles(touchedFiles)
                .changedMethods(changedMethods)
                .violations(List.of())
                .repairScope(repairScope)
                .build();
    }

    public static PatchScopeGuardResult failed(String failureType,
                                                List<String> touchedFiles,
                                                List<String> changedMethods,
                                                List<String> violations,
                                                Map<String, Object> repairScope) {
        return PatchScopeGuardResult.builder()
                .passed(false)
                .failureType(failureType)
                .touchedFiles(touchedFiles)
                .changedMethods(changedMethods)
                .violations(violations)
                .repairScope(repairScope)
                .build();
    }
}
