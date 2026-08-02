package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeAttachmentExpectation;
import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.runtime.RuntimeCommandPolicy;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 将执行计划中的真实仓库目录与隔离临时 Home 物化为进程可使用的目录事实。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeWorkspaceMaterializer {

    private static final java.util.Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final java.util.Set<PosixFilePermission>
            ATTACHMENT_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("r-x------");

    private final Path temporaryRoot;
    private final RuntimeExecPolicyMaterializer execPolicyMaterializer;
    private final UploadedConversationAttachmentStorage attachmentStorage;

    public RuntimeWorkspaceMaterializer(Path temporaryRoot) {
        this(temporaryRoot, new RuntimeExecPolicyMaterializer(
                RuntimeCommandPolicy.platformDefault()), null);
    }

    public RuntimeWorkspaceMaterializer(
            Path temporaryRoot,
            UploadedConversationAttachmentStorage attachmentStorage) {
        this(temporaryRoot, new RuntimeExecPolicyMaterializer(
                        RuntimeCommandPolicy.platformDefault()),
                Objects.requireNonNull(
                        attachmentStorage, "attachmentStorage"));
    }

    RuntimeWorkspaceMaterializer(
            Path temporaryRoot,
            RuntimeExecPolicyMaterializer execPolicyMaterializer) {
        this(temporaryRoot, execPolicyMaterializer, null);
    }

    RuntimeWorkspaceMaterializer(
            Path temporaryRoot,
            RuntimeExecPolicyMaterializer execPolicyMaterializer,
            UploadedConversationAttachmentStorage attachmentStorage) {
        this.temporaryRoot = Objects.requireNonNull(temporaryRoot, "temporaryRoot")
                .toAbsolutePath().normalize();
        this.execPolicyMaterializer = Objects.requireNonNull(
                execPolicyMaterializer, "execPolicyMaterializer");
        this.attachmentStorage = attachmentStorage;
    }

    public MaterializedWorkspace materialize(AgentExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Path primary = requireRealDirectory(
                plan.getWorkspaceLayout().getPrimaryRepositoryRoot(),
                "primary repository root");
        List<Path> readable = realDirectories(
                plan.getWorkspaceLayout().getReadableRoots(), "readable repository root");
        List<Path> writable = realDirectories(
                plan.getWorkspaceLayout().getWritableRoots(), "writable repository root");
        if (!readable.contains(primary)) {
            throw new IllegalStateException(
                    "materialized readable roots do not contain primary repository");
        }
        if (!readable.containsAll(writable)) {
            throw new IllegalStateException(
                    "materialized writable roots exceed readable repositories");
        }
        requireNoRepositoryRuntimeConfiguration(readable);
        Path executionRoot = temporaryRoot.resolve("exec-"
                + CanonicalHashing.sha256(plan.getExecutionIdentity().getExecutionId())
                .substring(0, 24));
        Path isolatedHome = executionRoot.resolve("home");
        Path attachmentRoot = executionRoot.resolve("attachments");
        try {
            Files.createDirectories(temporaryRoot);
            secureDirectory(temporaryRoot);
            Files.createDirectory(executionRoot);
            secureDirectory(executionRoot);
            Files.createDirectory(isolatedHome);
            secureDirectory(isolatedHome);
            Files.createDirectory(attachmentRoot);
            secureDirectory(attachmentRoot);
            execPolicyMaterializer.materialize(isolatedHome);
            copyUploadedAttachments(plan, attachmentRoot);
            secureAttachmentDirectory(attachmentRoot);
            return new MaterializedWorkspace(
                    executionRoot, isolatedHome, attachmentRoot,
                    primary, readable, writable);
        } catch (IOException | RuntimeException ex) {
            new RuntimeCleanup().cleanup(executionRoot, null);
            throw new IllegalStateException(
                    "runtime workspace could not be materialized");
        }
    }

    private void copyUploadedAttachments(
            AgentExecutionPlan plan, Path attachmentRoot) {
        for (RuntimeAttachmentExpectation expectation
                : plan.getAttachmentExpectations()) {
            if (!expectation.isUploadedConversation()) {
                continue;
            }
            if (attachmentStorage == null) {
                throw new IllegalStateException(
                        "uploaded attachment storage is unavailable");
            }
            attachmentStorage.copyVerified(
                    expectation.getStorageKey(),
                    attachmentRoot.resolve(expectation.getRuntimeFileName()),
                    expectation.getContentHash(), expectation.getSize());
        }
    }

    private void requireNoRepositoryRuntimeConfiguration(
            List<Path> repositoryRoots) {
        for (Path repositoryRoot : repositoryRoots) {
            Path codexDirectory = repositoryRoot.resolve(".codex");
            if (Files.exists(codexDirectory.resolve("config.toml"),
                    LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(codexDirectory.resolve("rules"),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(
                        "repository Runtime configuration is not allowed");
            }
        }
    }

    private List<Path> realDirectories(List<String> declared, String name) {
        List<Path> result = new ArrayList<Path>();
        for (String root : declared) {
            result.add(requireRealDirectory(root, name));
        }
        return Collections.unmodifiableList(result);
    }

    private Path requireRealDirectory(String declared, String name) {
        Path path = java.nio.file.Paths.get(declared).toAbsolutePath().normalize();
        try {
            Path real = path.toRealPath();
            if (!real.equals(path)) {
                throw new IllegalStateException(name + " must already be a real path");
            }
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(name + " is not a directory");
            }
            return real;
        } catch (IOException ex) {
            throw new IllegalStateException(name + " is unavailable", ex);
        }
    }

    private void secureDirectory(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Windows 权限由运行服务账户边界承担。
        }
    }

    private void secureAttachmentDirectory(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    path, ATTACHMENT_DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Windows 权限由运行服务账户和 Codex Sandbox 共同承担。
        }
    }

    /**
     * 一次 Runtime 启动所绑定的不可变真实目录布局。
     */
    @Getter
    public static final class MaterializedWorkspace {

        private final Path executionRoot;
        private final Path isolatedHome;
        private final Path attachmentRoot;
        private final Path primaryRepositoryRoot;
        private final List<Path> readableRoots;
        private final List<Path> writableRoots;

        private MaterializedWorkspace(
                                      Path executionRoot, Path isolatedHome,
                                      Path attachmentRoot,
                                      Path primaryRepositoryRoot,
                                      List<Path> readableRoots, List<Path> writableRoots) {
            this.executionRoot = executionRoot;
            this.isolatedHome = isolatedHome;
            this.attachmentRoot = attachmentRoot;
            this.primaryRepositoryRoot = primaryRepositoryRoot;
            this.readableRoots = Collections.unmodifiableList(
                    new ArrayList<Path>(readableRoots));
            this.writableRoots = Collections.unmodifiableList(
                    new ArrayList<Path>(writableRoots));
        }
    }
}
