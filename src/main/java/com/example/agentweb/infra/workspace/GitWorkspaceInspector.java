package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceInspection;
import com.example.agentweb.app.workbench.WorkspaceInspectionSource;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.WorkspaceRepositoryCandidate;
import com.example.agentweb.app.workbench.port.WorkspaceInspector;
import com.example.agentweb.app.workbench.port.WorkspaceScopeGateway;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 受允许根约束的 Git Workspace Inspect 与 Repository Scope 解析器。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class GitWorkspaceInspector implements WorkspaceInspector, WorkspaceScopeGateway {

    private static final int MANIFEST_MAXIMUM_BYTES = 1024 * 1024;
    private static final Set<String> IGNORED_DIRECTORIES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "target", "build", "node_modules", "data", ".worktrees")));

    private final WorkspacePathPolicy workspacePathPolicy;
    private final int discoveryMaximumDepth;
    private final int maximumRepositories;
    private final Duration inspectionTimeout;
    private final WorkspaceGitCommandRunner git;

    @Autowired
    public GitWorkspaceInspector(
            WorkspacePathPolicy workspacePathPolicy,
            @Value("${agent.workbench.workspace.discovery-max-depth:2}")
            int discoveryMaximumDepth,
            @Value("${agent.workbench.workspace.max-repositories:50}")
            int maximumRepositories,
            @Value("${agent.workbench.workspace.inspect-timeout-seconds:30}")
            long inspectionTimeoutSeconds,
            @Value("${agent.workbench.workspace.git-command-timeout-seconds:10}")
            long gitCommandTimeoutSeconds,
            @Value("${agent.workbench.workspace.max-git-output-bytes:8388608}")
            int maximumGitOutputBytes) {
        this(workspacePathPolicy, discoveryMaximumDepth, maximumRepositories,
                Duration.ofSeconds(inspectionTimeoutSeconds),
                new ProcessWorkspaceGitCommandRunner(
                        Duration.ofSeconds(gitCommandTimeoutSeconds), maximumGitOutputBytes));
    }

    GitWorkspaceInspector(WorkspacePathPolicy workspacePathPolicy, int discoveryMaximumDepth,
                          int maximumRepositories, Duration inspectionTimeout,
                          WorkspaceGitCommandRunner git) {
        if (workspacePathPolicy == null || git == null) {
            throw new IllegalArgumentException("workspace path policy and Git runner are required");
        }
        if (discoveryMaximumDepth < 1 || maximumRepositories < 1) {
            throw new IllegalArgumentException("workspace discovery limits must be positive");
        }
        if (inspectionTimeout == null || inspectionTimeout.isZero()
                || inspectionTimeout.isNegative()) {
            throw new IllegalArgumentException("workspace inspection timeout must be positive");
        }
        this.workspacePathPolicy = workspacePathPolicy;
        this.discoveryMaximumDepth = discoveryMaximumDepth;
        this.maximumRepositories = maximumRepositories;
        this.inspectionTimeout = inspectionTimeout;
        this.git = git;
    }

    @Override
    public WorkspaceInspection inspect(String workspaceRoot) {
        Path root = allowedRealRoot(workspaceRoot);
        long deadline = deadline();
        InspectionSelection inspectionSelection = inspectionSelection(root, deadline);
        List<WorkspaceRepositoryCandidate> candidates = inspectCandidates(
                root, inspectionSelection, deadline);
        return new WorkspaceInspection(root.toString(), UUID.randomUUID().toString(),
                inspectionSelection.source, candidates, Collections.<String>emptyList());
    }

    @Override
    public RepositoryScope resolve(String workspaceRoot, RepositorySelection selection) {
        if (selection == null) {
            throw failure(WorkspaceFailureCode.WORKSPACE_SELECTION_INVALID,
                    "an explicit repository selection is required", null);
        }
        if (selection.size() > maximumRepositories) {
            throw failure(WorkspaceFailureCode.WORKSPACE_DISCOVERY_LIMIT_EXCEEDED,
                    "repository selection exceeds the configured limit", null);
        }
        Path root = allowedRealRoot(workspaceRoot);
        List<ResolvedRepository> resolved = new ArrayList<ResolvedRepository>();
        Map<Path, String> keyByRealRoot = new LinkedHashMap<Path, String>();
        for (String repositoryKey : selection.getRepositoryKeys()) {
            Path candidate = candidatePath(root, repositoryKey);
            RepositoryFacts facts = inspectRepository(root, repositoryKey, candidate);
            String duplicate = keyByRealRoot.put(facts.realRoot, repositoryKey);
            if (duplicate != null) {
                throw failure(WorkspaceFailureCode.WORKSPACE_REPOSITORY_OVERLAP,
                        "repository selection resolves more than once to the same repository",
                        null);
            }
            try {
                resolved.add(ResolvedRepository.fromVerifiedFacts(repositoryKey,
                        facts.realRoot.toString(),
                        WorkspaceFileSystemSecurity.rootFingerprint(facts.realRoot), false));
            } catch (IOException ex) {
                throw failure(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                        "repository identity could not be verified", ex);
            }
        }
        try {
            return RepositoryScope.create(root.toString(), selection, resolved,
                    maximumRepositories);
        } catch (IllegalArgumentException ex) {
            WorkspaceFailureCode code = ex.getMessage() != null
                    && (ex.getMessage().contains("contain each other")
                    || ex.getMessage().contains("roots must be unique"))
                    ? WorkspaceFailureCode.WORKSPACE_REPOSITORY_OVERLAP
                    : WorkspaceFailureCode.WORKSPACE_SELECTION_INVALID;
            throw failure(code, "repository selection cannot form a safe scope", ex);
        }
    }

    private InspectionSelection inspectionSelection(Path root, long deadline) {
        Path manifest = root.resolve(".agent-web.yml");
        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return manifestSelection(manifest);
        }
        if (WorkspaceFileSystemSecurity.hasGitEntry(root)) {
            String rootKey = rootRepositoryKey(root);
            return new InspectionSelection(WorkspaceInspectionSource.ROOT_REPOSITORY,
                    RepositorySelection.of(rootKey, Collections.singletonList(rootKey)));
        }
        List<String> discovered = discoverRepositoryKeys(root, deadline);
        if (discovered.isEmpty()) {
            throw failure(WorkspaceFailureCode.WORKSPACE_SELECTION_REQUIRED,
                    "workspace does not contain a selectable Git repository", null);
        }
        return new InspectionSelection(WorkspaceInspectionSource.DISCOVERY,
                RepositorySelection.of(discovered.get(0), discovered));
    }

    private InspectionSelection manifestSelection(Path manifest) {
        if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(
                manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(WorkspaceFailureCode.WORKSPACE_MANIFEST_INVALID,
                    "workspace manifest must be a regular non-symbolic-link file", null);
        }
        try {
            if (Files.size(manifest) > MANIFEST_MAXIMUM_BYTES) {
                throw failure(WorkspaceFailureCode.WORKSPACE_MANIFEST_INVALID,
                        "workspace manifest exceeds the configured size limit", null);
            }
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8));
            Map<String, Object> rootMap = stringMap(loaded, "workspace manifest");
            Map<String, Object> workbench = stringMap(rootMap.get("workbench"),
                    "workbench manifest section");
            List<String> repositories = stringList(workbench.get("repositories"));
            if (repositories.isEmpty()) {
                throw failure(WorkspaceFailureCode.WORKSPACE_MANIFEST_INVALID,
                        "workspace manifest must select at least one repository", null);
            }
            String primary = optionalText(workbench.get("primary_repository"));
            if (primary == null) {
                primary = repositories.get(0);
            }
            RepositorySelection selection = RepositorySelection.of(primary, repositories);
            if (selection.size() > maximumRepositories) {
                throw failure(WorkspaceFailureCode.WORKSPACE_DISCOVERY_LIMIT_EXCEEDED,
                        "workspace manifest exceeds the configured repository limit", null);
            }
            return new InspectionSelection(WorkspaceInspectionSource.MANIFEST, selection);
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw failure(WorkspaceFailureCode.WORKSPACE_MANIFEST_INVALID,
                    "workspace manifest is invalid", ex);
        }
    }

    private List<String> discoverRepositoryKeys(Path root, long deadline) {
        List<Path> candidates = new ArrayList<Path>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    discoveryMaximumDepth, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path directory,
                                                                 BasicFileAttributes attributes) {
                            requireBeforeDeadline(deadline);
                            if (!directory.equals(root)) {
                                String name = directory.getFileName().toString();
                                if (IGNORED_DIRECTORIES.contains(name)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                if (WorkspaceFileSystemSecurity.hasGitEntry(directory)) {
                                    candidates.add(directory);
                                    if (candidates.size() > maximumRepositories) {
                                        throw failure(
                                                WorkspaceFailureCode.WORKSPACE_DISCOVERY_LIMIT_EXCEEDED,
                                                "workspace discovery exceeds the configured repository limit",
                                                null);
                                    }
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                    "workspace repository discovery could not read the allowed root", ex);
        }
        candidates.sort(Comparator.comparing(Path::toString));
        List<String> keys = new ArrayList<String>();
        for (Path candidate : candidates) {
            keys.add(portable(root.relativize(candidate)));
        }
        return keys;
    }

    private List<WorkspaceRepositoryCandidate> inspectCandidates(
            Path root, InspectionSelection inspectionSelection, long deadline) {
        List<WorkspaceRepositoryCandidate> result =
                new ArrayList<WorkspaceRepositoryCandidate>();
        Map<Path, String> keyByRealRoot = new LinkedHashMap<Path, String>();
        for (String repositoryKey : inspectionSelection.selection.getRepositoryKeys()) {
            requireBeforeDeadline(deadline);
            RepositoryFacts facts = inspectRepository(
                    root, repositoryKey, candidatePath(root, repositoryKey));
            String duplicate = keyByRealRoot.put(facts.realRoot, repositoryKey);
            if (duplicate != null) {
                throw failure(WorkspaceFailureCode.WORKSPACE_REPOSITORY_OVERLAP,
                        "workspace candidates resolve more than once to the same repository", null);
            }
            List<String> warnings = facts.clean
                    ? Collections.<String>emptyList()
                    : Collections.singletonList("WORKTREE_DIRTY");
            result.add(new WorkspaceRepositoryCandidate(repositoryKey, repositoryKey,
                    facts.branch, shortHead(facts.head), facts.clean, true,
                    repositoryKey.equals(
                            inspectionSelection.selection.getPrimaryRepositoryKey()), warnings));
        }
        return result;
    }

    private RepositoryFacts inspectRepository(Path workspaceRoot, String repositoryKey,
                                              Path candidate) {
        rejectSymbolicPath(workspaceRoot, candidate);
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                || !WorkspaceFileSystemSecurity.hasGitEntry(candidate)
                || Files.isSymbolicLink(candidate.resolve(".git"))) {
            throw failure(WorkspaceFailureCode.WORKSPACE_REPOSITORY_NOT_FOUND,
                    "selected repository is not an accessible Git repository", null);
        }
        try {
            Path realRoot = candidate.toRealPath();
            if (!realRoot.startsWith(workspaceRoot)) {
                throw failure(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                        "selected repository escapes the allowed workspace root", null);
            }
            WorkspaceGitCommandResult topLevelResult = git.execute(realRoot,
                    "git", "rev-parse", "--show-toplevel");
            if (topLevelResult.getExitCode() != 0) {
                throw failure(WorkspaceFailureCode.WORKSPACE_REPOSITORY_NOT_FOUND,
                        "selected repository cannot resolve its Git top-level", null);
            }
            Path topLevel = Paths.get(requiredText(topLevelResult,
                    WorkspaceFailureCode.WORKSPACE_REPOSITORY_NOT_FOUND,
                    "Git top-level is unavailable")).toRealPath();
            if (!topLevel.equals(realRoot)) {
                throw failure(WorkspaceFailureCode.WORKSPACE_REPOSITORY_NOT_FOUND,
                        "selected path is not a Git top-level", null);
            }
            WorkspaceGitCommandResult headResult = git.execute(realRoot,
                    "git", "rev-parse", "--verify", "HEAD");
            if (headResult.getExitCode() != 0) {
                throw failure(WorkspaceFailureCode.WORKSPACE_REPOSITORY_HEAD_MISSING,
                        "selected repository does not have a resolvable HEAD", null);
            }
            String head = requiredText(headResult,
                    WorkspaceFailureCode.WORKSPACE_REPOSITORY_HEAD_MISSING,
                    "selected repository HEAD is empty");
            WorkspaceGitCommandResult branchResult = git.execute(realRoot,
                    "git", "symbolic-ref", "--short", "-q", "HEAD");
            String branch = branchResult.getExitCode() == 0
                    ? optionalText(branchResult) : "DETACHED";
            if (branch == null) {
                branch = "DETACHED";
            }
            WorkspaceGitCommandResult statusResult = git.execute(realRoot,
                    "git", "status", "--porcelain=v1", "-z", "--untracked-files=all",
                    "--no-renames");
            if (statusResult.getExitCode() != 0) {
                throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                        "selected repository status could not be inspected", null);
            }
            return new RepositoryFacts(realRoot, branch, head,
                    statusResult.getOutput().length == 0);
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                    "selected repository path identity could not be verified", ex);
        }
    }

    private void rejectSymbolicPath(Path root, Path candidate) {
        Path relative;
        try {
            relative = root.relativize(candidate);
        } catch (IllegalArgumentException ex) {
            throw failure(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                    "selected repository is outside the allowed workspace root", ex);
        }
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw failure(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                        "selected repository path must not contain symbolic links", null);
            }
        }
    }

    private Path candidatePath(Path root, String repositoryKey) {
        if (WorkspaceFileSystemSecurity.hasGitEntry(root)
                && rootRepositoryKey(root).equals(repositoryKey)) {
            return root;
        }
        return root.resolve(repositoryKey).normalize();
    }

    private Path allowedRealRoot(String workspaceRoot) {
        try {
            return Paths.get(workspacePathPolicy.requireExistingDirectory(workspaceRoot));
        } catch (IllegalArgumentException ex) {
            throw failure(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                    "workspace root is outside configured allowed roots", ex);
        }
    }

    private long deadline() {
        long timeoutNanos = inspectionTimeout.toNanos();
        long current = System.nanoTime();
        long candidate = current + timeoutNanos;
        return candidate < current ? Long.MAX_VALUE : candidate;
    }

    private void requireBeforeDeadline(long deadline) {
        if (System.nanoTime() > deadline) {
            throw failure(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                    "workspace inspection exceeded its configured timeout", null);
        }
    }

    private String requiredText(WorkspaceGitCommandResult result,
                                WorkspaceFailureCode code, String message) {
        String value = optionalText(result);
        if (value == null) {
            throw failure(code, message, null);
        }
        return value;
    }

    private String optionalText(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private String optionalText(WorkspaceGitCommandResult result) {
        String value = new String(result.getOutput(), StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? null : value;
    }

    private String rootRepositoryKey(Path root) {
        Path fileName = root.getFileName();
        if (fileName == null) {
            throw failure(WorkspaceFailureCode.WORKSPACE_SELECTION_REQUIRED,
                    "filesystem root cannot be used as a repository key", null);
        }
        return fileName.toString();
    }

    private String shortHead(String head) {
        return head.substring(0, Math.min(7, head.length()));
    }

    private String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private Map<String, Object> stringMap(Object value, String field) {
        if (!(value instanceof Map)) {
            throw failure(WorkspaceFailureCode.WORKSPACE_MANIFEST_INVALID,
                    field + " must be a YAML mapping", null);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() == null) {
                throw failure(WorkspaceFailureCode.WORKSPACE_MANIFEST_INVALID,
                        field + " contains a null key", null);
            }
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List)) {
            throw failure(WorkspaceFailureCode.WORKSPACE_MANIFEST_INVALID,
                    "workbench.repositories must be a YAML list", null);
        }
        List<String> result = new ArrayList<String>();
        for (Object entry : (List<?>) value) {
            String repository = optionalText(entry);
            if (repository == null) {
                throw failure(WorkspaceFailureCode.WORKSPACE_MANIFEST_INVALID,
                        "workbench.repositories contains a blank entry", null);
            }
            result.add(repository);
        }
        return result;
    }

    private WorkspaceOperationException failure(WorkspaceFailureCode code, String message,
                                                Throwable cause) {
        return new WorkspaceOperationException(code, message, cause);
    }

    private static final class InspectionSelection {
        private final WorkspaceInspectionSource source;
        private final RepositorySelection selection;

        private InspectionSelection(WorkspaceInspectionSource source,
                                    RepositorySelection selection) {
            this.source = source;
            this.selection = selection;
        }
    }

    private static final class RepositoryFacts {
        private final Path realRoot;
        private final String branch;
        private final String head;
        private final boolean clean;

        private RepositoryFacts(Path realRoot, String branch, String head, boolean clean) {
            this.realRoot = realRoot;
            this.branch = branch;
            this.head = head;
            this.clean = clean;
        }
    }
}
