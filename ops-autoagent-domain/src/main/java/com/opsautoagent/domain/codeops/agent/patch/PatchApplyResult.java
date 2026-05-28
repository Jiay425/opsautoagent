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
public class PatchApplyResult {

    private boolean requested;

    private boolean applied;

    private boolean checkPassed;

    private String repositoryPath;

    private List<String> command;

    private int exitCode;

    private String output;

    private String errorMessage;

    public static PatchApplyResult skipped(String repositoryPath, String reason) {
        return PatchApplyResult.builder()
                .requested(false)
                .applied(false)
                .checkPassed(false)
                .repositoryPath(repositoryPath == null ? "" : repositoryPath)
                .command(List.of())
                .exitCode(-1)
                .output("")
                .errorMessage(reason)
                .build();
    }

    public Map<String, Object> toRawOutput() {
        return Map.of(
                "requested", requested,
                "applied", applied,
                "checkPassed", checkPassed,
                "repositoryPath", repositoryPath == null ? "" : repositoryPath,
                "command", command == null ? List.of() : command,
                "exitCode", exitCode,
                "output", output == null ? "" : output,
                "errorMessage", errorMessage == null ? "" : errorMessage
        );
    }

}
