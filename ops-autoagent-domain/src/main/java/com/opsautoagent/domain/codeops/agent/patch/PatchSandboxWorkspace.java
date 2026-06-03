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
public class PatchSandboxWorkspace {

    private boolean enabled;

    private boolean isolated;

    private String mode;

    private String originalRepositoryPath;

    private String sandboxRepositoryPath;

    private String branchName;

    private List<String> command;

    private String errorMessage;

    public static PatchSandboxWorkspace direct(String repositoryPath, String reason) {
        return PatchSandboxWorkspace.builder()
                .enabled(false)
                .isolated(false)
                .mode("DIRECT_REPOSITORY")
                .originalRepositoryPath(repositoryPath == null ? "" : repositoryPath)
                .sandboxRepositoryPath(repositoryPath == null ? "" : repositoryPath)
                .branchName("")
                .command(List.of())
                .errorMessage(reason == null ? "" : reason)
                .build();
    }

    public Map<String, Object> toRawOutput() {
        return Map.of(
                "enabled", enabled,
                "isolated", isolated,
                "mode", mode == null ? "" : mode,
                "originalRepositoryPath", originalRepositoryPath == null ? "" : originalRepositoryPath,
                "sandboxRepositoryPath", sandboxRepositoryPath == null ? "" : sandboxRepositoryPath,
                "branchName", branchName == null ? "" : branchName,
                "command", command == null ? List.of() : command,
                "errorMessage", errorMessage == null ? "" : errorMessage
        );
    }
}
