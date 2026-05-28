package com.opsautoagent.domain.codeops.agent.patch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PatchValidationResult {

    private boolean patchPresent;

    private boolean valid;

    private String repositoryPath;

    private List<String> touchedFiles;

    private List<String> existingTouchedFiles;

    private List<String> missingTouchedFiles;

    private List<String> errors;

    private List<String> warnings;

    public Map<String, Object> toRawOutput() {
        return Map.of(
                "patchPresent", patchPresent,
                "valid", valid,
                "repositoryPath", value(repositoryPath),
                "touchedFiles", list(touchedFiles),
                "existingTouchedFiles", list(existingTouchedFiles),
                "missingTouchedFiles", list(missingTouchedFiles),
                "errors", list(errors),
                "warnings", list(warnings)
        );
    }

    private List<String> list(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

}
