package com.opsautoagent.domain.codeops.agent.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

/**
 * Agent permission policy — defines what an agent task can and cannot do.
 * Inspired by Claude Code's 5-layer defense and Codex's sandboxed execution.
 *
 * Layers:
 *   1. Read scope — which directories/files can be read
 *   2. Write scope — which files can be modified (enforced by PatchScopeGuard)
 *   3. Command allowlist — which shell commands can run
 *   4. Blocked patterns — never-allow dangerous operations
 *   5. Rollback guard — snapshot before write, restore on failure
 */
@Slf4j
@Service
public class AgentPermissionPolicy {

    private static final List<String> GLOBAL_BLOCKED_PATTERNS = List.of(
            "rm -rf", "rm -r", "sudo", "chmod", "chown",
            "DROP TABLE", "DELETE FROM", "TRUNCATE",
            "> /dev/", "dd if=", "mkfs", ":(){ :|:& };:",
            "wget", "curl.*-o", "eval", "$(",
            "/etc/passwd", "/etc/shadow", ".ssh/", ".env"
    );

    private static final List<String> ALLOWED_COMMANDS = List.of(
            "mvn", "git", "java", "javac",
            "ls", "cat", "head", "tail", "grep", "find",
            "diff", "wc", "echo", "mkdir"
    );

    /**
     * Build a permission policy for a given task context.
     */
    public PolicyDecision evaluate(String repositoryPath, String taskType, String severity) {
        Map<String, Object> policy = new LinkedHashMap<>();

        // Read scope: project sources, logs, fixtures
        List<String> readPaths = List.of(
                repositoryPath + "/src/**",
                repositoryPath + "/pom.xml",
                "data/fixtures/**",
                "data/log/**",
                "docs/**"
        );
        policy.put("allowedReadPaths", readPaths);

        // Write scope: only the target repository
        List<String> writePaths = List.of(
                repositoryPath + "/src/**"
        );
        policy.put("allowedWritePaths", writePaths);

        // Command allowlist: build/test/inspect, never mutate outside repo
        policy.put("allowedCommands", ALLOWED_COMMANDS);

        // Blocked: destructive operations always blocked
        policy.put("blockedPatterns", GLOBAL_BLOCKED_PATTERNS);

        // Policy metadata
        policy.put("taskType", taskType);
        policy.put("severity", severity);
        policy.put("patchGuardEnabled", true);
        policy.put("rollbackEnabled", true);
        policy.put("requiresHumanApproval", "CRITICAL".equalsIgnoreCase(severity));

        return new PolicyDecision(policy);
    }

    /**
     * Check if a command is allowed under this policy.
     */
    public boolean isCommandAllowed(String command) {
        if (command == null || command.isBlank()) return false;
        String lower = command.toLowerCase().trim();

        // Check blocked patterns first
        for (String blocked : GLOBAL_BLOCKED_PATTERNS) {
            if (lower.contains(blocked.toLowerCase())) {
                log.warn("BLOCKED command: {} matches blocked pattern: {}", command, blocked);
                return false;
            }
        }

        // Check allowlist
        String firstWord = command.split("\\s+")[0];
        for (String allowed : ALLOWED_COMMANDS) {
            if (firstWord.equals(allowed)) return true;
        }

        log.warn("COMMAND_NOT_ALLOWED: {} (first word: {})", command, firstWord);
        return false;
    }

    public boolean isWriteAllowed(String repositoryPath, String relativePath) {
        if (repositoryPath == null || repositoryPath.isBlank()
                || relativePath == null || relativePath.isBlank()) {
            return false;
        }
        Path repo = Path.of(repositoryPath).toAbsolutePath().normalize();
        Path target = repo.resolve(relativePath).normalize();
        if (!target.startsWith(repo)) {
            log.warn("WRITE_NOT_ALLOWED: {} escapes repository {}", relativePath, repositoryPath);
            return false;
        }
        Path srcRoot = repo.resolve("src").normalize();
        boolean allowed = target.startsWith(srcRoot);
        if (!allowed) {
            log.warn("WRITE_NOT_ALLOWED: {} is outside {}", relativePath, srcRoot);
        }
        return allowed;
    }

    public Map<String, Object> governanceSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("policyVersion", "codeops-agent-permission-v1");
        summary.put("layers", List.of(
                "read scope",
                "write scope",
                "command allowlist",
                "blocked dangerous patterns",
                "patch guard",
                "snapshot rollback",
                "human approval"
        ));
        summary.put("allowedCommands", ALLOWED_COMMANDS);
        summary.put("blockedPatterns", GLOBAL_BLOCKED_PATTERNS);
        summary.put("writeScope", "repository src/** only");
        summary.put("blockedWriteTargets", List.of(".env", ".ssh/**", "pom.xml without approval", "scripts/config secrets"));
        summary.put("patchGuardEnabled", true);
        summary.put("rollbackEnabled", true);
        summary.put("defaultApprovalRule", "HIGH/CRITICAL risk patch or guardrail reasons require human approval");
        return summary;
    }

    public static class PolicyDecision {
        private final Map<String, Object> policy;

        public PolicyDecision(Map<String, Object> policy) { this.policy = policy; }

        @SuppressWarnings("unchecked")
        public List<String> getAllowedReadPaths() {
            return (List<String>) policy.getOrDefault("allowedReadPaths", List.of());
        }

        @SuppressWarnings("unchecked")
        public List<String> getAllowedWritePaths() {
            return (List<String>) policy.getOrDefault("allowedWritePaths", List.of());
        }

        public boolean requiresHumanApproval() {
            return Boolean.TRUE.equals(policy.get("requiresHumanApproval"));
        }

        public Map<String, Object> toMap() { return policy; }
    }
}
