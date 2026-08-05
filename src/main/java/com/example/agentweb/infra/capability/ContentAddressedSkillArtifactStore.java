package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityArtifactIntegrityException;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.shared.CanonicalHashing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Skill Package 的内容寻址不可变文件存储。
 *
 * @author alex
 * @since 2026-08-05
 */
final class ContentAddressedSkillArtifactStore {

    private static final long MAX_PACKAGE_BYTES = 32L * 1024L * 1024L;
    private static final Set<OpenOption> READ_NOFOLLOW = Set.of(
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private final Path configuredRoot;

    ContentAddressedSkillArtifactStore(Path configuredRoot) {
        if (configuredRoot == null) {
            throw new IllegalArgumentException("Skill Artifact Root is required");
        }
        this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
    }

    StoredSkillArtifact archive(SkillPackage skillPackage) {
        requireCompletePackage(skillPackage);
        Path root = secureRoot();
        String artifactKey = artifactKey(skillPackage.getPackageHash());
        Path target = resolveArtifactDirectory(root, artifactKey);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            SkillArtifactContent content = read(artifactKey, skillPackage.getManifest());
            validateContent(skillPackage, content);
            return new StoredSkillArtifact(artifactKey, content.totalBytes);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory(root, ".skill-artifact-");
            secureDirectory(temporary);
            long size = writePackage(temporary, skillPackage);
            Files.createDirectories(target.getParent());
            secureDirectoryTree(root, target.getParent());
            moveDirectory(temporary, target);
            SkillArtifactContent content = read(artifactKey, skillPackage.getManifest());
            validateContent(skillPackage, content);
            return new StoredSkillArtifact(artifactKey, size);
        } catch (IOException failure) {
            throw integrity("cannot archive Skill Package", failure);
        } finally {
            deleteRecursively(temporary);
        }
    }

    SkillArtifactContent read(String artifactKey, SkillManifest manifest) {
        Path root = secureRoot();
        Path artifactDirectory = resolveArtifactDirectory(root, artifactKey);
        secureDirectoryTree(root, artifactDirectory);
        byte[] entryBytes = readFile(
                artifactDirectory, manifest.getEntryPath(), MAX_PACKAGE_BYTES);
        Map<String, byte[]> resources = new LinkedHashMap<String, byte[]>();
        long total = entryBytes.length;
        for (String resourcePath : manifest.getResourcePaths()) {
            byte[] bytes = readFile(
                    artifactDirectory, resourcePath, MAX_PACKAGE_BYTES - total);
            total += bytes.length;
            resources.put(resourcePath, bytes);
        }
        return new SkillArtifactContent(
                new String(entryBytes, StandardCharsets.UTF_8), resources, total);
    }

    private void requireCompletePackage(SkillPackage skillPackage) {
        if (skillPackage == null) {
            throw new IllegalArgumentException("Skill Package is required");
        }
        if (!skillPackage.getResourceContents().keySet().equals(
                skillPackage.getManifest().getResourcePaths())) {
            throw integrity("Skill Package contents are incomplete");
        }
        String calculated = packageHash(skillPackage.getResourceHashes());
        if (!skillPackage.getPackageHash().equals(calculated)) {
            throw integrity("Skill Package Hash facts are inconsistent");
        }
    }

    private void validateContent(
            SkillPackage expected, SkillArtifactContent content) {
        try {
            new SkillPackage(expected.getManifest(), expected.getPackageHash(),
                    content.getEntryContent(), expected.getResourceHashes(),
                    content.getResourceContents());
        } catch (RuntimeException failure) {
            throw integrity("Skill Artifact content does not match Package Hash", failure);
        }
    }

    private long writePackage(Path temporary, SkillPackage skillPackage)
            throws IOException {
        byte[] entry = skillPackage.getEntryContent().getBytes(StandardCharsets.UTF_8);
        long size = entry.length;
        writeFile(temporary, skillPackage.getManifest().getEntryPath(), entry);
        for (Map.Entry<String, byte[]> resource
                : skillPackage.getResourceContents().entrySet()) {
            size += resource.getValue().length;
            if (size > MAX_PACKAGE_BYTES) {
                throw integrity("Skill Package exceeds Artifact size limit");
            }
            writeFile(temporary, resource.getKey(), resource.getValue());
        }
        if (size < 1L) {
            throw integrity("Skill Package Artifact must not be empty");
        }
        return size;
    }

    private void writeFile(Path artifactDirectory, String relativePath, byte[] bytes)
            throws IOException {
        Path target = resolvePackageFile(artifactDirectory, relativePath);
        Files.createDirectories(target.getParent());
        secureDirectoryTree(artifactDirectory, target.getParent());
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        secureFile(target);
    }

    private byte[] readFile(
            Path artifactDirectory, String relativePath, long remainingBytes) {
        if (remainingBytes < 0L) {
            throw integrity("Skill Package exceeds Artifact size limit");
        }
        Path target = resolvePackageFile(artifactDirectory, relativePath);
        secureDirectoryTree(artifactDirectory, target.getParent());
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
            throw integrity("Skill Artifact file is missing or unsafe");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(target, READ_NOFOLLOW)) {
            if (channel.size() > remainingBytes || channel.size() > MAX_PACKAGE_BYTES) {
                throw integrity("Skill Artifact file exceeds size limit");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.toIntExact(channel.size()));
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw integrity("cannot read Skill Artifact file", failure);
        }
    }

    private Path secureRoot() {
        try {
            if (Files.isSymbolicLink(configuredRoot)) {
                throw integrity("Skill Artifact Root must not be a symbolic link");
            }
            Files.createDirectories(configuredRoot);
            if (!Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw integrity("Skill Artifact Root is not a directory");
            }
            secureDirectory(configuredRoot);
            return configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw integrity("Skill Artifact Root is unavailable", failure);
        }
    }

    private Path resolveArtifactDirectory(Path root, String artifactKey) {
        if (artifactKey == null
                || !artifactKey.matches("skills/[0-9a-f]{2}/[0-9a-f]{64}")) {
            throw integrity("Skill Artifact Key is invalid");
        }
        Path resolved = root.resolve(artifactKey).normalize();
        if (!resolved.startsWith(root)) {
            throw integrity("Skill Artifact Key escapes Artifact Root");
        }
        return resolved;
    }

    private Path resolvePackageFile(Path artifactDirectory, String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw integrity("Skill Artifact relative path is required");
        }
        Path relative;
        try {
            relative = Path.of(relativePath.trim());
        } catch (RuntimeException failure) {
            throw integrity("Skill Artifact relative path is invalid", failure);
        }
        if (relative.isAbsolute() || containsNativeInstruction(relative)) {
            throw integrity("Skill Artifact relative path is forbidden");
        }
        Path resolved = artifactDirectory.resolve(relative).normalize();
        if (!resolved.startsWith(artifactDirectory)) {
            throw integrity("Skill Artifact relative path escapes package");
        }
        return resolved;
    }

    private void secureDirectoryTree(Path root, Path directory) {
        if (directory == null || !directory.normalize().startsWith(root.normalize())) {
            throw integrity("Skill Artifact directory escapes root");
        }
        Path current = root;
        Path relative = root.relativize(directory);
        for (Path element : relative) {
            current = current.resolve(element);
            if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw integrity("Skill Artifact directory is missing or unsafe");
            }
        }
    }

    private boolean containsNativeInstruction(Path relative) {
        for (Path element : relative) {
            String name = element.toString();
            if ("AGENTS.md".equalsIgnoreCase(name)
                    || "CLAUDE.md".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private String artifactKey(String packageHash) {
        if (packageHash == null || !packageHash.matches("[0-9a-f]{64}")) {
            throw integrity("Skill Package Hash is invalid");
        }
        return "skills/" + packageHash.substring(0, 2) + "/" + packageHash;
    }

    private String packageHash(Map<String, String> resourceHashes) {
        if (resourceHashes == null || resourceHashes.isEmpty()) {
            throw integrity("Skill Package resource Hashes are required");
        }
        StringBuilder canonical = new StringBuilder();
        Map<String, String> sorted = new TreeMap<String, String>(resourceHashes);
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            CanonicalHashing.appendFramed(canonical, "path", entry.getKey());
            CanonicalHashing.appendFramed(canonical, "sha256", entry.getValue());
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private void moveDirectory(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temporary, target);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // 相同内容寻址目标由随后读取和 Hash 复验确认。
        }
    }

    private void secureDirectory(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 平台由服务进程账户边界保护。
        }
    }

    private void secureFile(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 平台由服务进程账户边界保护。
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> ordered = new ArrayList<Path>();
            paths.forEach(ordered::add);
            ordered.sort(Comparator.reverseOrder());
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 孤立临时目录由后续维护任务清理，不影响已归档内容。
        }
    }

    private CapabilityArtifactIntegrityException integrity(String message) {
        return new CapabilityArtifactIntegrityException(
                "WORKBENCH_CAPABILITY_ARTIFACT_INTEGRITY_FAILED", message);
    }

    private CapabilityArtifactIntegrityException integrity(
            String message, Throwable cause) {
        return new CapabilityArtifactIntegrityException(
                "WORKBENCH_CAPABILITY_ARTIFACT_INTEGRITY_FAILED", message, cause);
    }

    static final class StoredSkillArtifact {
        private final String artifactKey;
        private final long artifactSize;

        StoredSkillArtifact(String artifactKey, long artifactSize) {
            this.artifactKey = artifactKey;
            this.artifactSize = artifactSize;
        }

        String getArtifactKey() {
            return artifactKey;
        }

        long getArtifactSize() {
            return artifactSize;
        }
    }

    static final class SkillArtifactContent {
        private final String entryContent;
        private final Map<String, byte[]> resourceContents;
        private final long totalBytes;

        SkillArtifactContent(
                String entryContent, Map<String, byte[]> resourceContents,
                long totalBytes) {
            this.entryContent = entryContent;
            this.resourceContents = Collections.unmodifiableMap(
                    new LinkedHashMap<String, byte[]>(resourceContents));
            this.totalBytes = totalBytes;
        }

        String getEntryContent() {
            return entryContent;
        }

        Map<String, byte[]> getResourceContents() {
            return resourceContents;
        }

        long getTotalBytes() {
            return totalBytes;
        }
    }
}
