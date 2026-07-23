package com.example.agentweb.infra.harness;

import com.example.agentweb.config.harness.HarnessCatalogProperties;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.ArtifactStoreException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 受控根目录下的 Artifact 正文存储。物理路径只使用 ID 的 SHA-256，避免原始 ID 路径逃逸。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class FileSystemArtifactStore implements ArtifactStore {

    private final Path root;

    @Autowired
    public FileSystemArtifactStore(HarnessCatalogProperties properties) {
        this(Paths.get(properties.getArtifactRoot()));
    }

    FileSystemArtifactStore(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("artifact root must not be null");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void store(ArtifactDescriptor descriptor, ArtifactContent content) {
        requireMatchingContent(descriptor, content);
        Path target = target(descriptor);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            secureDirectoryTree(target.getParent());
            if (Files.exists(target)) {
                secureFile(target);
                verifyStored(target, descriptor);
                return;
            }
            temporary = Files.createTempFile(target.getParent(), ".artifact-", ".tmp");
            secureFile(temporary);
            Files.write(temporary, content.copyBytes());
            moveAtomically(temporary, target);
            temporary = null;
            secureFile(target);
            verifyStored(target, descriptor);
        } catch (IOException ex) {
            throw new ArtifactStoreException("could not store artifact "
                    + descriptor.getArtifactId() + "@" + descriptor.getVersion(), ex);
        } finally {
            deleteTemporary(temporary);
        }
    }

    @Override
    public ArtifactContent read(ArtifactDescriptor descriptor) {
        Path target = target(descriptor);
        try {
            ArtifactContent content = ArtifactContent.from(Files.readAllBytes(target));
            requireMatchingContent(descriptor, content);
            return content;
        } catch (IOException ex) {
            throw new ArtifactStoreException("could not read artifact "
                    + descriptor.getArtifactId() + "@" + descriptor.getVersion(), ex);
        }
    }

    private void requireMatchingContent(ArtifactDescriptor descriptor, ArtifactContent content) {
        if (descriptor == null || content == null) {
            throw new ArtifactStoreException("artifact descriptor and content must not be null");
        }
        if (descriptor.getSizeBytes() != content.getSizeBytes()
                || !descriptor.getSha256().equals(content.getSha256())) {
            throw new ArtifactStoreException("artifact content does not match descriptor hash");
        }
    }

    private Path target(ArtifactDescriptor descriptor) {
        if (descriptor == null) {
            throw new ArtifactStoreException("artifact descriptor must not be null");
        }
        String runBucket = hash(descriptor.getRunId());
        String artifactName = hash(descriptor.getArtifactId()) + "-v" + descriptor.getVersion()
                + "-" + descriptor.getSha256() + ".artifact";
        Path resolved = root.resolve(runBucket)
                .resolve(descriptor.getStage().name().toLowerCase())
                .resolve(artifactName)
                .normalize();
        if (!resolved.startsWith(root)) {
            throw new ArtifactStoreException("artifact path escapes configured root");
        }
        return resolved;
    }

    private String hash(String value) {
        return ArtifactContent.from(value.getBytes(StandardCharsets.UTF_8)).getSha256();
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        } catch (FileAlreadyExistsException ex) {
            verifyStored(target, null);
        }
    }

    private void verifyStored(Path target, ArtifactDescriptor descriptor) throws IOException {
        if (descriptor == null) {
            if (!Files.isRegularFile(target)) {
                throw new ArtifactStoreException("artifact target is not a regular file");
            }
            return;
        }
        ArtifactContent stored = ArtifactContent.from(Files.readAllBytes(target));
        requireMatchingContent(descriptor, stored);
    }

    private void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // 临时文件清理失败不覆盖主异常，Artifact Root 运维扫描可回收 .tmp。
        }
    }

    private void secureDirectoryTree(Path leaf) throws IOException {
        secureDirectory(root);
        Path current = root;
        for (Path segment : root.relativize(leaf)) {
            current = current.resolve(segment);
            secureDirectory(current);
        }
    }

    private void secureDirectory(Path directory) throws IOException {
        setPosixPermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    private void secureFile(Path file) throws IOException {
        setPosixPermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    private void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 等非 POSIX 文件系统没有该视图，继续依赖平台 ACL。
        }
    }
}
