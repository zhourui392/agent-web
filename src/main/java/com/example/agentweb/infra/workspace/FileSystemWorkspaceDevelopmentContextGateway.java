package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.port.WorkspaceDevelopmentContextGateway;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentContext;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentContextClassifier;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentMarker;
import com.example.agentweb.domain.workbench.WorkspaceDevelopmentContext;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 在 Repository Scope 内对有限根级 marker 做无正文、NOFOLLOW_LINKS 探测。
 *
 * <p>Adapter 在探测前重新核对 Workspace/Repository 真实路径和仓库根指纹；marker 只接受
 * Catalog 中的根级普通文件，symlink、目录和超限文件全部忽略。返回值不包含真实路径。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class FileSystemWorkspaceDevelopmentContextGateway
        implements WorkspaceDevelopmentContextGateway {

    static final long DEFAULT_MAXIMUM_MARKER_BYTES = 1024L * 1024L;

    private final RepositoryDevelopmentContextClassifier classifier;
    private final long maximumMarkerBytes;

    public FileSystemWorkspaceDevelopmentContextGateway() {
        this(new RepositoryDevelopmentContextClassifier(), DEFAULT_MAXIMUM_MARKER_BYTES);
    }

    FileSystemWorkspaceDevelopmentContextGateway(
            RepositoryDevelopmentContextClassifier classifier, long maximumMarkerBytes) {
        if (classifier == null) {
            throw new IllegalArgumentException(
                    "repository development context classifier is required");
        }
        if (maximumMarkerBytes < 1L) {
            throw new IllegalArgumentException(
                    "repository development marker byte limit must be positive");
        }
        this.classifier = classifier;
        this.maximumMarkerBytes = maximumMarkerBytes;
    }

    @Override
    public WorkspaceDevelopmentContext inspect(RepositoryScope repositoryScope) {
        if (repositoryScope == null) {
            throw new IllegalArgumentException("repository scope is required");
        }
        Path workspaceRoot = requireStableDirectory(
                repositoryScope.getWorkspaceRoot(), "workspace root identity changed");
        List<RepositoryDevelopmentContext> repositories =
                new ArrayList<RepositoryDevelopmentContext>();
        for (ResolvedRepository repository : repositoryScope.getRepositories()) {
            Path repositoryRoot = requireStableRepository(workspaceRoot, repository);
            repositories.add(classifier.classify(
                    repository.getRepositoryKey(), detectMarkers(repositoryRoot)));
        }
        return WorkspaceDevelopmentContext.create(
                repositoryScope.getScopeHash(),
                repositoryScope.getPrimaryRepositoryKey(),
                repositories);
    }

    private Path requireStableRepository(
            Path workspaceRoot, ResolvedRepository repository) {
        Path repositoryRoot = requireStableDirectory(
                repository.getRepositoryRoot(), "repository root identity changed");
        if (!repositoryRoot.startsWith(workspaceRoot)) {
            throw scopeViolation("repository root escaped the authorized workspace");
        }
        try {
            String currentFingerprint =
                    WorkspaceFileSystemSecurity.rootFingerprint(repositoryRoot);
            if (!repository.getRootFingerprint().equals(currentFingerprint)) {
                throw scopeViolation("repository root identity changed");
            }
            return repositoryRoot;
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw scopeViolation("repository root identity could not be verified");
        }
    }

    private Path requireStableDirectory(String rawPath, String safeMessage) {
        try {
            Path path = Paths.get(rawPath);
            if (!path.isAbsolute() || !path.equals(path.normalize())
                    || Files.isSymbolicLink(path)
                    || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw scopeViolation(safeMessage);
            }
            Path currentRealPath = path.toRealPath();
            if (!path.equals(currentRealPath)) {
                throw scopeViolation(safeMessage);
            }
            return currentRealPath;
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw scopeViolation(safeMessage);
        }
    }

    private EnumSet<RepositoryDevelopmentMarker> detectMarkers(Path repositoryRoot) {
        EnumSet<RepositoryDevelopmentMarker> detected =
                EnumSet.noneOf(RepositoryDevelopmentMarker.class);
        for (RepositoryDevelopmentMarker marker : RepositoryDevelopmentMarker.values()) {
            Path markerPath = repositoryRoot.resolve(marker.getRelativePath());
            BasicFileAttributes attributes = readMarkerAttributes(markerPath);
            if (attributes != null && attributes.isRegularFile()
                    && !attributes.isSymbolicLink()
                    && attributes.size() <= maximumMarkerBytes) {
                detected.add(marker);
            }
        }
        return detected;
    }

    private BasicFileAttributes readMarkerAttributes(Path markerPath) {
        try {
            return Files.readAttributes(markerPath, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            return null;
        } catch (IOException | RuntimeException ex) {
            throw scopeViolation("repository development marker could not be inspected");
        }
    }

    private WorkspaceOperationException scopeViolation(String safeMessage) {
        return new WorkspaceOperationException(
                WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION, safeMessage);
    }

}
