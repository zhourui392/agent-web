package com.example.agentweb.infra.workspace.document;

import com.example.agentweb.app.workbench.document.DocumentFailureCode;
import com.example.agentweb.app.workbench.document.DocumentOperationException;
import com.example.agentweb.app.workbench.WorkspaceOperationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * 对 Scoped Path 做最多两次全链重试的稳定、有界、NOFOLLOW 文件读取器。
 *
 * @author alex
 * @since 2026-08-01
 */
final class StableDocumentReader {

    private static final int MAXIMUM_ATTEMPTS = 2;
    private static final int BUFFER_SIZE = 8192;
    private static final DocumentReadObserver NO_OP = (path, attempt) -> { };

    private final DocumentReadObserver observer;

    StableDocumentReader() {
        this(NO_OP);
    }

    StableDocumentReader(DocumentReadObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    StableDocumentSnapshot read(DocumentPathSource source, long maximumBytes) {
        if (source == null) {
            throw new IllegalArgumentException("document path source is required");
        }
        if (maximumBytes < 1L || maximumBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "document read limit must be between 1 and Integer.MAX_VALUE");
        }
        for (int attempt = 1; attempt <= MAXIMUM_ATTEMPTS; attempt++) {
            ReadAttempt result = attempt(source, maximumBytes, attempt);
            if (result.stable) {
                return new StableDocumentSnapshot(
                        result.content, result.after.lastModifiedTime().toMillis());
            }
        }
        throw new DocumentOperationException(
                DocumentFailureCode.WORKBENCH_DOCUMENT_CHANGED_DURING_READ,
                "document changed during both bounded read attempts");
    }

    private ReadAttempt attempt(
            DocumentPathSource source, long maximumBytes, int attempt) {
        try {
            Path path = normalized(source.resolve());
            BasicFileAttributes before = attributes(path);
            requireRegular(before);
            requireSize(before.size(), maximumBytes);
            byte[] content = boundedRead(path, maximumBytes);
            observer.afterRead(path, attempt);
            BasicFileAttributes after = attributes(path);
            requireRegular(after);
            Path resolvedAfter = normalized(source.resolve());
            boolean stable = path.equals(resolvedAfter)
                    && sameIdentity(before, after)
                    && after.size() == content.length;
            return new ReadAttempt(content, after, stable);
        } catch (DocumentOperationException ex) {
            throw ex;
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (NoSuchFileException ex) {
            throw notFound(ex);
        } catch (IOException | RuntimeException ex) {
            throw notFound(ex);
        }
    }

    private Path normalized(Path path) {
        if (path == null) {
            throw notFound(null);
        }
        return path.toAbsolutePath().normalize();
    }

    private BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private void requireRegular(BasicFileAttributes attributes) {
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.isOther()) {
            throw notFound(null);
        }
    }

    private void requireSize(long size, long maximumBytes) {
        if (size < 0L) {
            throw notFound(null);
        }
        if (size > maximumBytes) {
            throw new DocumentOperationException(
                    DocumentFailureCode.WORKBENCH_DOCUMENT_TOO_LARGE,
                    "document exceeds the configured bounded read limit");
        }
    }

    private byte[] boundedRead(Path path, long maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(maximumBytes, BUFFER_SIZE));
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        try (InputStream input = Files.newInputStream(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                total += count;
                if (total > maximumBytes) {
                    throw new DocumentOperationException(
                            DocumentFailureCode.WORKBENCH_DOCUMENT_TOO_LARGE,
                            "document exceeds the configured bounded read limit");
                }
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private boolean sameIdentity(
            BasicFileAttributes before, BasicFileAttributes after) {
        return Objects.equals(before.fileKey(), after.fileKey())
                && before.creationTime().equals(after.creationTime())
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && before.size() == after.size();
    }

    private DocumentOperationException notFound(Throwable cause) {
        return new DocumentOperationException(
                DocumentFailureCode.WORKBENCH_DOCUMENT_NOT_FOUND,
                "document is not an accessible regular scoped file", cause);
    }

    private static final class ReadAttempt {

        private final byte[] content;
        private final BasicFileAttributes after;
        private final boolean stable;

        private ReadAttempt(
                byte[] content, BasicFileAttributes after, boolean stable) {
            this.content = content;
            this.after = after;
            this.stable = stable;
        }
    }
}
