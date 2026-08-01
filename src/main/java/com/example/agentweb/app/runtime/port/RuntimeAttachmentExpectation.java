package com.example.agentweb.app.runtime.port;

import lombok.Getter;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runtime 启动前必须重新核验的附件身份和内容摘要。
 *
 * <p>仓库根仅存在于私有执行计划，不进入 Snapshot API、SSE 或日志；
 * {@link #toString()} 有意只输出字段名。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeAttachmentExpectation {

    private static final int MAXIMUM_LOGICAL_PATH_LENGTH = 4096;

    private final String repositoryKey;
    private final String repositoryRoot;
    private final String relativePath;
    private final String contentHash;
    private final long size;

    public RuntimeAttachmentExpectation(
            String repositoryKey, String repositoryRoot,
            String relativePath, String contentHash, long size) {
        this.repositoryKey = requireLogicalPath(
                repositoryKey, "attachment repository key");
        this.repositoryRoot = requireNormalizedAbsoluteRoot(repositoryRoot);
        this.relativePath = requireLogicalPath(
                relativePath, "attachment relative path");
        if (contentHash == null
                || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "attachment content hash must be lowercase SHA-256");
        }
        if (size < 0L || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "attachment size must be bounded");
        }
        this.contentHash = contentHash;
        this.size = size;
    }

    private static String requireLogicalPath(String value, String name) {
        if (value == null || value.trim().isEmpty()
                || value.length() > MAXIMUM_LOGICAL_PATH_LENGTH
                || value.indexOf('\\') >= 0 || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*")
                || containsControlCharacter(value)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)
                    || "..".equals(segment)) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
        return value;
    }

    private static String requireNormalizedAbsoluteRoot(String value) {
        if (value == null || value.trim().isEmpty()
                || containsControlCharacter(value)) {
            throw new IllegalArgumentException(
                    "attachment repository root is invalid");
        }
        try {
            Path path = Paths.get(value);
            if (!path.isAbsolute() || !path.normalize().equals(path)) {
                throw new IllegalArgumentException(
                        "attachment repository root must be normalized and absolute");
            }
            return path.toString();
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException(
                    "attachment repository root is invalid", failure);
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "RuntimeAttachmentExpectation{repositoryKey, relativePath, "
                + "contentHash, size}";
    }
}
