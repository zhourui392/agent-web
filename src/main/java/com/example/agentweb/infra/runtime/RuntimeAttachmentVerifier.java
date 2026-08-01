package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeAttachmentExpectation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * 在本地进程启动紧前重新核验附件文件身份、大小和 exact SHA-256。
 *
 * <p>本适配器只流式计算摘要，不保留正文。前后文件身份检查可发现核验期间的
 * 删除、替换和符号链接变化；核验返回到操作系统真正创建子进程之间仍存在无法由
 * Java 路径 API 完全消除的极小竞态，因此调用方必须把本步骤放在
 * {@code ProcessBuilder.start()} 紧前。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
final class RuntimeAttachmentVerifier {

    private static final int BUFFER_SIZE = 8192;
    private static final AttachmentReadObserver NO_OP = path -> { };

    private final AttachmentReadObserver observer;

    RuntimeAttachmentVerifier() {
        this(NO_OP);
    }

    RuntimeAttachmentVerifier(AttachmentReadObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    void verify(AgentExecutionPlan plan) {
        Objects.requireNonNull(plan, "runtime execution plan");
        try {
            for (RuntimeAttachmentExpectation expectation
                    : plan.getAttachmentExpectations()) {
                verify(expectation);
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(
                    "runtime attachment verification failed");
        }
    }

    private void verify(RuntimeAttachmentExpectation expectation)
            throws IOException {
        Path root = Paths.get(expectation.getRepositoryRoot());
        requireExactRoot(root);
        Path candidate = root.resolve(
                expectation.getRelativePath()).normalize();
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            throw new IOException("invalid attachment identity");
        }
        rejectSymbolicLinks(root, candidate);
        BasicFileAttributes before = attributes(candidate);
        requireExactRegularFile(before, expectation.getSize());
        HashObservation observed = hash(candidate, expectation.getSize());
        observer.afterHash(candidate);
        rejectSymbolicLinks(root, candidate);
        BasicFileAttributes after = attributes(candidate);
        requireExactRegularFile(after, expectation.getSize());
        Path realAfter = candidate.toRealPath();
        if (!candidate.equals(realAfter)
                || !sameIdentity(before, after)
                || observed.size != expectation.getSize()
                || !observed.contentHash.equals(
                expectation.getContentHash())) {
            throw new IOException("attachment changed during verification");
        }
    }

    private void requireExactRoot(Path root) throws IOException {
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.equals(root.toRealPath())) {
            throw new IOException("attachment repository is unavailable");
        }
    }

    private void rejectSymbolicLinks(Path root, Path candidate)
            throws IOException {
        Path current = root;
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("attachment path contains a symbolic link");
            }
        }
    }

    private BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(
                path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
    }

    private void requireExactRegularFile(
            BasicFileAttributes attributes, long expectedSize)
            throws IOException {
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.isOther()
                || attributes.size() != expectedSize) {
            throw new IOException("attachment is not the expected regular file");
        }
    }

    private HashObservation hash(Path path, long expectedSize)
            throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        try (InputStream input = Files.newInputStream(
                path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                total += count;
                if (total > expectedSize) {
                    throw new IOException("attachment exceeded expected size");
                }
                digest.update(buffer, 0, count);
            }
        }
        return new HashObservation(hex(digest.digest()), total);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private boolean sameIdentity(
            BasicFileAttributes before, BasicFileAttributes after) {
        return Objects.equals(before.fileKey(), after.fileKey())
                && before.creationTime().equals(after.creationTime())
                && before.lastModifiedTime().equals(
                after.lastModifiedTime())
                && before.size() == after.size();
    }

    @FunctionalInterface
    interface AttachmentReadObserver {

        void afterHash(Path path) throws IOException;
    }

    private static final class HashObservation {

        private final String contentHash;
        private final long size;

        private HashObservation(String contentHash, long size) {
            this.contentHash = contentHash;
            this.size = size;
        }
    }
}
