package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PatchAttemptEntity {

    private Integer attemptNo;

    private String skillId;

    private String editMethod;

    private Map<String, Object> inputFailureDiagnostic;

    private List<String> filesRead;

    private Map<String, Object> scopeDecision;

    private Map<String, Object> applyResult;

    private Map<String, Object> sourceValidationResult;

    private Map<String, Object> compileResult;

    private Map<String, Object> testResult;

    private Map<String, Object> nextFailureDiagnostic;

    private Boolean recovered;

    private LocalDateTime createTime;

    public Map<String, Object> toRawOutput() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("attemptNo", attemptNo == null ? 0 : attemptNo);
        raw.put("skillId", skillId == null ? "" : skillId);
        raw.put("editMethod", editMethod == null ? "" : editMethod);
        raw.put("inputFailureDiagnostic", inputFailureDiagnostic == null ? Map.of() : inputFailureDiagnostic);
        raw.put("filesRead", filesRead == null ? List.of() : filesRead);
        raw.put("scopeDecision", scopeDecision == null ? Map.of() : scopeDecision);
        raw.put("applyResult", applyResult == null ? Map.of() : applyResult);
        raw.put("sourceValidationResult", sourceValidationResult == null ? Map.of() : sourceValidationResult);
        raw.put("compileResult", compileResult == null ? Map.of() : compileResult);
        raw.put("testResult", testResult == null ? Map.of() : testResult);
        raw.put("nextFailureDiagnostic", nextFailureDiagnostic == null ? Map.of() : nextFailureDiagnostic);
        raw.put("recovered", Boolean.TRUE.equals(recovered));
        raw.put("createTime", createTime == null ? "" : createTime.toString());
        return raw;
    }
}
