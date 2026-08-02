package com.example.agentweb.app.runtime.port;

import lombok.Getter;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

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

    public enum Type {
        REPOSITORY_DOCUMENT,
        UPLOADED_CONVERSATION
    }

    private final Type type;
    private final String repositoryKey;
    private final String repositoryRoot;
    private final String relativePath;
    private final String attachmentId;
    private final String storageKey;
    private final String runtimeFileName;
    private final String contentHash;
    private final long size;

    public RuntimeAttachmentExpectation(
            String repositoryKey, String repositoryRoot,
            String relativePath, String contentHash, long size) {
        this(Type.REPOSITORY_DOCUMENT,
                repositoryKey, repositoryRoot, relativePath,
                null, null, null, contentHash, size);
    }

    private RuntimeAttachmentExpectation(
            Type type, String repositoryKey, String repositoryRoot,
            String relativePath, String attachmentId, String storageKey,
            String runtimeFileName, String contentHash, long size) {
        this.type = Objects.requireNonNull(type, "attachment type");
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
        if (type == Type.REPOSITORY_DOCUMENT) {
            this.repositoryKey = requireLogicalPath(
                    repositoryKey, "attachment repository key");
            this.repositoryRoot = requireNormalizedAbsoluteRoot(repositoryRoot);
            this.relativePath = requireLogicalPath(
                    relativePath, "attachment relative path");
            this.attachmentId = null;
            this.storageKey = null;
            this.runtimeFileName = null;
            return;
        }
        if (size < 1L) {
            throw new IllegalArgumentException(
                    "uploaded attachment size must be positive");
        }
        this.repositoryKey = null;
        this.repositoryRoot = null;
        this.relativePath = null;
        this.attachmentId = requireOpaqueIdentifier(attachmentId);
        this.storageKey = requireSha256(
                storageKey, "uploaded attachment storage key");
        this.runtimeFileName = requireRuntimeFileName(runtimeFileName);
    }

    public static RuntimeAttachmentExpectation uploadedConversation(
            String attachmentId, String storageKey, String runtimeFileName,
            String contentHash, long size) {
        return new RuntimeAttachmentExpectation(
                Type.UPLOADED_CONVERSATION,
                null, null, null,
                attachmentId, storageKey, runtimeFileName,
                contentHash, size);
    }

    public boolean isRepositoryDocument() {
        return type == Type.REPOSITORY_DOCUMENT;
    }

    public boolean isUploadedConversation() {
        return type == Type.UPLOADED_CONVERSATION;
    }

    public String logicalIdentity() {
        if (isRepositoryDocument()) {
            return type.name() + ":" + repositoryRoot + ":" + relativePath;
        }
        return type.name() + ":" + attachmentId;
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

    private static String requireOpaqueIdentifier(String value) {
        if (value == null
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(
                    "uploaded attachment identity is invalid");
        }
        return value;
    }

    private static String requireRuntimeFileName(String value) {
        if (value == null || value.contains("..")
                || !value.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(
                    "uploaded attachment runtime file name is invalid");
        }
        return value;
    }

    private static String requireSha256(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be lowercase SHA-256");
        }
        return value;
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
        return "RuntimeAttachmentExpectation{type, logicalIdentity, "
                + "contentHash, size}";
    }
}
