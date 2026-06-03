package com.opsautoagent.domain.codeops.agent.patch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PatchSandboxService {

    @Value("${codeops.patch.sandbox.enabled:true}")
    private boolean sandboxEnabled;

    @Value("${codeops.patch.sandbox.base-dir:data/codeops-sandbox}")
    private String sandboxBaseDir;

    @Value("${codeops.patch.sandbox.prefer-git-worktree:false}")
    private boolean preferGitWorktree;

    @Value("${codeops.patch.sandbox.timeout-ms:30000}")
    private long timeoutMs;

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", "target", "build", ".gradle", ".idea", "node_modules", "data");

    public PatchSandboxWorkspace prepare(String repositoryPath, String taskId) {
        if (!sandboxEnabled) {
            return PatchSandboxWorkspace.direct(repositoryPath, "Patch sandbox disabled by codeops.patch.sandbox.enabled=false.");
        }
        if (isBlank(repositoryPath)) {
            return PatchSandboxWorkspace.direct(repositoryPath, "Repository path is blank; sandbox cannot be created.");
        }
        Path original = Path.of(repositoryPath).toAbsolutePath().normalize();
        if (!Files.exists(original) || !Files.isDirectory(original)) {
            return PatchSandboxWorkspace.direct(repositoryPath, "Repository path does not exist; sandbox cannot be created.");
        }
        Path sandbox = Path.of(sandboxBaseDir)
                .resolve(safe(taskId) + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")))
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(sandbox.getParent());
            if (preferGitWorktree && Files.exists(original.resolve(".git"))) {
                PatchSandboxWorkspace worktree = tryGitWorktree(original, sandbox, taskId);
                if (worktree.isIsolated()) {
                    writeManifest(sandbox, worktree, taskId);
                    return worktree;
                }
            }
            copyRepository(original, sandbox);
            PatchSandboxWorkspace workspace = PatchSandboxWorkspace.builder()
                    .enabled(true)
                    .isolated(true)
                    .mode("COPY_SANDBOX")
                    .originalRepositoryPath(original.toString())
                    .sandboxRepositoryPath(sandbox.toString())
                    .branchName("")
                    .command(List.of())
                    .errorMessage("")
                    .build();
            writeManifest(sandbox, workspace, taskId);
            return workspace;
        } catch (IOException e) {
            log.warn("Create patch sandbox failed. repository={}, sandbox={}", original, sandbox, e);
            return PatchSandboxWorkspace.direct(repositoryPath, "Patch sandbox creation failed: " + e.getMessage());
        }
    }

    private PatchSandboxWorkspace tryGitWorktree(Path original, Path sandbox, String taskId) {
        String branchName = "codeops/" + safe(taskId);
        List<String> command = List.of("git", "worktree", "add", "-B", branchName, sandbox.toString(), "HEAD");
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(original.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return PatchSandboxWorkspace.direct(original.toString(), "git worktree add timeout");
            }
            String output = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0) {
                log.warn("git worktree add failed, fallback to copy sandbox. output={}", output);
                return PatchSandboxWorkspace.direct(original.toString(), "git worktree add failed: " + output);
            }
            return PatchSandboxWorkspace.builder()
                    .enabled(true)
                    .isolated(true)
                    .mode("GIT_WORKTREE")
                    .originalRepositoryPath(original.toString())
                    .sandboxRepositoryPath(sandbox.toString())
                    .branchName(branchName)
                    .command(command)
                    .errorMessage("")
                    .build();
        } catch (Exception e) {
            log.warn("git worktree add failed, fallback to copy sandbox. repository={}", original, e);
            return PatchSandboxWorkspace.direct(original.toString(), "git worktree add failed: " + e.getMessage());
        }
    }

    private void copyRepository(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (!relative.toString().isBlank() && shouldSkipDirectory(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (!shouldSkipDirectory(relative)) {
                    Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void writeManifest(Path sandbox, PatchSandboxWorkspace workspace, String taskId) {
        try {
            Map<String, Object> manifest = new java.util.LinkedHashMap<>(workspace.toRawOutput());
            manifest.put("taskId", taskId == null ? "" : taskId);
            manifest.put("createTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            manifest.put("purpose", "CodeOps patch sandbox. Patches, compile gates and tests run here before any human decision.");
            Files.writeString(sandbox.resolve("CODEOPS_SANDBOX_MANIFEST.json"),
                    com.alibaba.fastjson.JSON.toJSONString(manifest, true),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Write patch sandbox manifest failed. sandbox={}", sandbox, e);
        }
    }

    private boolean shouldSkipDirectory(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (SKIPPED_DIRECTORIES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private String safe(String value) {
        String raw = isBlank(value) ? "incident" : value;
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
