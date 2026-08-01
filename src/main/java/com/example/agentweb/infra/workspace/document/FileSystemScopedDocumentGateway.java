package com.example.agentweb.infra.workspace.document;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.document.DocumentContentView;
import com.example.agentweb.app.workbench.document.DocumentDirectoryEntryView;
import com.example.agentweb.app.workbench.document.DocumentDirectoryQuery;
import com.example.agentweb.app.workbench.document.DocumentDirectoryView;
import com.example.agentweb.app.workbench.document.DocumentDownloadView;
import com.example.agentweb.app.workbench.document.DocumentEntryKind;
import com.example.agentweb.app.workbench.document.DocumentFailureCode;
import com.example.agentweb.app.workbench.document.DocumentKind;
import com.example.agentweb.app.workbench.document.DocumentOperationException;
import com.example.agentweb.app.workbench.document.port.ScopedDocumentGateway;
import com.example.agentweb.config.workbench.WorkbenchDocumentProperties;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.WorkspaceSensitivePathPolicy;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.example.agentweb.infra.workspace.ScopedPathResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Repository Scope 与运行期 Workspace 白名单双重约束的文件系统文档适配器。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class FileSystemScopedDocumentGateway implements ScopedDocumentGateway {

    private static final Comparator<DocumentDirectoryEntryView> ENTRY_ORDER =
            Comparator.comparingInt(FileSystemScopedDocumentGateway::kindOrder)
                    .thenComparing(DocumentDirectoryEntryView::getName,
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(DocumentDirectoryEntryView::getName);

    private final ScopedPathResolver pathResolver;
    private final WorkspacePathPolicy workspacePathPolicy;
    private final WorkbenchDocumentProperties properties;
    private final DocumentTypeResolver typeResolver;
    private final StableDocumentReader stableReader;

    @Autowired
    public FileSystemScopedDocumentGateway(
            ScopedPathResolver pathResolver,
            WorkspacePathPolicy workspacePathPolicy,
            WorkbenchDocumentProperties properties,
            DocumentTypeResolver typeResolver) {
        this(pathResolver, workspacePathPolicy, properties, typeResolver,
                new StableDocumentReader());
    }

    FileSystemScopedDocumentGateway(
            ScopedPathResolver pathResolver,
            WorkspacePathPolicy workspacePathPolicy,
            WorkbenchDocumentProperties properties,
            DocumentTypeResolver typeResolver,
            StableDocumentReader stableReader) {
        this.pathResolver = Objects.requireNonNull(pathResolver, "pathResolver");
        this.workspacePathPolicy = Objects.requireNonNull(
                workspacePathPolicy, "workspacePathPolicy");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
        this.stableReader = Objects.requireNonNull(stableReader, "stableReader");
        properties.validate();
    }

    @Override
    public DocumentDirectoryView listTree(
            RepositoryScope scope, DocumentDirectoryQuery query) {
        Objects.requireNonNull(query, "query");
        int limit = Math.min(query.getLimit(), properties.getMaxDirectoryEntries());
        Path directory = allowedDirectory(resolveDirectory(scope, query));
        PriorityQueue<DocumentDirectoryEntryView> selected =
                new PriorityQueue<DocumentDirectoryEntryView>(
                        limit, ENTRY_ORDER.reversed());
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                DocumentDirectoryEntryView entry = entry(query, child);
                if (entry == null) {
                    continue;
                }
                if (selected.size() == limit) {
                    truncated = true;
                    if (ENTRY_ORDER.compare(entry, selected.peek()) < 0) {
                        selected.poll();
                        selected.add(entry);
                    }
                } else {
                    selected.add(entry);
                }
            }
        } catch (IOException ex) {
            throw notFound(ex);
        }
        Path verifiedAfter = allowedDirectory(resolveDirectory(scope, query));
        if (!directory.equals(verifiedAfter)) {
            throw changed();
        }
        List<DocumentDirectoryEntryView> entries =
                new ArrayList<DocumentDirectoryEntryView>(selected);
        Collections.sort(entries, ENTRY_ORDER);
        return new DocumentDirectoryView(
                query.getRepositoryKey(), query.getRelativePath(), entries, truncated);
    }

    @Override
    public DocumentContentView readContent(
            RepositoryScope scope, DocumentReference reference) {
        requireVisible(reference);
        StableDocumentSnapshot snapshot = read(scope, reference);
        byte[] content = snapshot.getContent();
        DocumentTypeResolution type = typeResolver.resolve(
                reference.getRelativePath(), content);
        if (type.getKind() == DocumentKind.IMAGE
                && snapshot.getSize() > properties.getMaxImageBytes()) {
            throw tooLarge();
        }
        long previewLimit = type.getKind() == DocumentKind.LOG_OR_REPORT
                ? properties.getMaxLogPreviewBytes()
                : properties.getMaxTextBytes();
        DocumentTextPreview preview = type.preview(content, (int) previewLimit);
        return new DocumentContentView(
                reference, type.getKind(), type.getMediaType(), type.getEncoding(),
                snapshot.getSize(), snapshot.getLastModified(),
                snapshot.getContentVersion(), preview.getContent(),
                preview.isTruncated(), false);
    }

    @Override
    public DocumentDownloadView download(
            RepositoryScope scope, DocumentReference reference) {
        requireVisible(reference);
        StableDocumentSnapshot snapshot = read(scope, reference);
        byte[] content = snapshot.getContent();
        DocumentTypeResolution type = typeResolver.resolve(
                reference.getRelativePath(), content);
        return new DocumentDownloadView(
                reference, fileName(reference), type.getMediaType(),
                snapshot.getLastModified(), snapshot.getContentVersion(), content);
    }

    @Override
    public DocumentDownloadView inlineImage(
            RepositoryScope scope, DocumentReference reference) {
        requireVisible(reference);
        StableDocumentSnapshot snapshot = read(
                scope, reference, properties.getMaxImageBytes());
        byte[] content = snapshot.getContent();
        DocumentTypeResolution type = typeResolver.resolve(
                reference.getRelativePath(), content);
        if (type.getKind() != DocumentKind.IMAGE) {
            throw unsupported();
        }
        return new DocumentDownloadView(
                reference, fileName(reference), type.getMediaType(),
                snapshot.getLastModified(), snapshot.getContentVersion(), content);
    }

    private StableDocumentSnapshot read(
            RepositoryScope scope, DocumentReference reference) {
        return read(scope, reference, properties.getMaxDownloadBytes());
    }

    private StableDocumentSnapshot read(
            RepositoryScope scope, DocumentReference reference,
            long maximumBytes) {
        return stableReader.read(() -> allowedFile(resolveFile(scope, reference)),
                maximumBytes);
    }

    private Path resolveDirectory(
            RepositoryScope scope, DocumentDirectoryQuery query) {
        try {
            return pathResolver.resolveDirectory(
                    scope, query.getRepositoryKey(), query.getRelativePath());
        } catch (WorkspaceOperationException ex) {
            if (ex.getCode() == WorkspaceFailureCode.WORKSPACE_TOPOLOGY_CHANGED) {
                throw ex;
            }
            throw notFound(ex);
        }
    }

    private Path resolveFile(
            RepositoryScope scope, DocumentReference reference) {
        try {
            return pathResolver.resolveExisting(
                    scope, reference.getRepositoryKey(), reference.getRelativePath());
        } catch (WorkspaceOperationException ex) {
            if (ex.getCode() == WorkspaceFailureCode.WORKSPACE_TOPOLOGY_CHANGED) {
                throw ex;
            }
            throw notFound(ex);
        }
    }

    private Path allowedDirectory(Path resolved) {
        try {
            Path allowed = Paths.get(workspacePathPolicy.requireExistingDirectory(
                    resolved.toString())).toRealPath();
            if (!resolved.equals(allowed)) {
                throw workspaceForbidden(null);
            }
            return allowed;
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw workspaceForbidden(ex);
        }
    }

    private Path allowedFile(Path resolved) {
        try {
            Path allowed = Paths.get(workspacePathPolicy.requireExistingFile(
                    resolved.toString())).toRealPath();
            if (!resolved.equals(allowed)) {
                throw workspaceForbidden(null);
            }
            return allowed;
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw workspaceForbidden(ex);
        }
    }

    private DocumentDirectoryEntryView entry(
            DocumentDirectoryQuery query, Path child) {
        String name = child.getFileName() == null
                ? "" : child.getFileName().toString();
        String relativePath = query.getRelativePath().isEmpty()
                ? name : query.getRelativePath() + "/" + name;
        try {
            DocumentReference.of(query.getRepositoryKey(), relativePath);
            if (WorkspaceSensitivePathPolicy.isSensitive(relativePath)) {
                return null;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                return null;
            }
            if (attributes.isDirectory()) {
                return new DocumentDirectoryEntryView(
                        name, relativePath, DocumentEntryKind.DIRECTORY,
                        null, attributes.lastModifiedTime().toMillis());
            }
            if (attributes.isRegularFile()) {
                return new DocumentDirectoryEntryView(
                        name, relativePath, DocumentEntryKind.FILE,
                        Long.valueOf(attributes.size()),
                        attributes.lastModifiedTime().toMillis());
            }
            return null;
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    private void requireVisible(DocumentReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (WorkspaceSensitivePathPolicy.isSensitive(reference.getRelativePath())) {
            throw notFound(null);
        }
    }

    private String fileName(DocumentReference reference) {
        String path = reference.getRelativePath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private WorkspaceOperationException workspaceForbidden(Throwable cause) {
        return new WorkspaceOperationException(
                WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                "document path is outside the current workspace allowlist", cause);
    }

    private DocumentOperationException notFound(Throwable cause) {
        return new DocumentOperationException(
                DocumentFailureCode.WORKBENCH_DOCUMENT_NOT_FOUND,
                "document is not available in the scoped repository", cause);
    }

    private DocumentOperationException tooLarge() {
        return new DocumentOperationException(
                DocumentFailureCode.WORKBENCH_DOCUMENT_TOO_LARGE,
                "document exceeds the configured preview limit");
    }

    private DocumentOperationException unsupported() {
        return new DocumentOperationException(
                DocumentFailureCode.WORKBENCH_DOCUMENT_UNSUPPORTED,
                "document is not a supported inline image");
    }

    private DocumentOperationException changed() {
        return new DocumentOperationException(
                DocumentFailureCode.WORKBENCH_DOCUMENT_CHANGED_DURING_READ,
                "document directory changed during listing");
    }

    private static int kindOrder(DocumentDirectoryEntryView entry) {
        return entry.getKind() == DocumentEntryKind.DIRECTORY ? 0 : 1;
    }
}
