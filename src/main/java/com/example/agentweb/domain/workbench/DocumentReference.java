package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workspace.RepositorySelection;
import lombok.Getter;

import java.util.Objects;
import java.util.Collections;

/**
 * Workbench 文件身份，只包含 Repository Key 与 POSIX 相对路径。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class DocumentReference implements Comparable<DocumentReference> {

    private final String repositoryKey;
    private final String relativePath;

    private DocumentReference(String repositoryKey, String relativePath) {
        String logicalRepositoryKey = requireRepositoryKey(repositoryKey);
        this.repositoryKey = RepositorySelection.of(
                logicalRepositoryKey,
                Collections.singletonList(logicalRepositoryKey))
                .getPrimaryRepositoryKey();
        this.relativePath = requireRelativePath(relativePath);
    }

    public static DocumentReference of(String repositoryKey, String relativePath) {
        return new DocumentReference(repositoryKey, relativePath);
    }

    @Override
    public int compareTo(DocumentReference other) {
        int repository = repositoryKey.compareTo(other.repositoryKey);
        return repository == 0 ? relativePath.compareTo(other.relativePath) : repository;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DocumentReference)) {
            return false;
        }
        DocumentReference that = (DocumentReference) other;
        return repositoryKey.equals(that.repositoryKey)
                && relativePath.equals(that.relativePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repositoryKey, relativePath);
    }

    @Override
    public String toString() {
        return repositoryKey + "/" + relativePath;
    }

    public static String requireRelativePath(String value) {
        String path = DomainText.require(value, "document relative path", 4096);
        if (path.indexOf('\\') >= 0 || path.startsWith("/")
                || path.matches("^[A-Za-z]:.*")
                || containsControlCharacter(path)) {
            throw new IllegalArgumentException(
                    "document path must be a POSIX relative path");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "document path must not contain empty, '.' or '..' segments");
            }
        }
        return path;
    }

    public static String requireRepositoryKey(String value) {
        String key = DomainText.require(value, "document repository key", 4096);
        if (key.indexOf('\\') >= 0 || key.startsWith("/")
                || key.matches("^[A-Za-z]:.*")
                || containsControlCharacter(key)) {
            throw new IllegalArgumentException(
                    "document repository key must be a logical POSIX key");
        }
        String[] segments = key.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)
                    || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "document repository key contains an invalid segment");
            }
        }
        return key;
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
