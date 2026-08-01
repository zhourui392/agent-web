package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository Scope 的规范化 JDBC 映射，读取时重新执行领域校验并核对持久化 Hash。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkbenchScopeJdbcMapper {

    private static final String COLUMNS = "workbench_id, repository_key, relative_path, "
            + "repository_root, root_fingerprint, primary_repository";

    private final JdbcTemplate jdbc;

    WorkbenchScopeJdbcMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(String workbenchId, RepositoryScope scope) {
        for (ResolvedRepository repository : scope.getRepositories()) {
            jdbc.update("INSERT INTO workbench_repository_scope (" + COLUMNS
                            + ") VALUES (?,?,?,?,?,?)",
                    workbenchId, repository.getRepositoryKey(), repository.getRelativePath(),
                    repository.getRepositoryRoot(), repository.getRootFingerprint(),
                    repository.getRepositoryKey().equals(scope.getPrimaryRepositoryKey())
                            ? 1 : 0);
        }
    }

    RepositoryScope load(String workbenchId) {
        List<ScopeParentRow> parents = jdbc.query(
                "SELECT workspace_root, primary_repository_key, repository_scope_hash "
                        + "FROM workbench WHERE id=?",
                (rs, rowNumber) -> new ScopeParentRow(
                        rs.getString("workspace_root"),
                        rs.getString("primary_repository_key"),
                        rs.getString("repository_scope_hash")),
                workbenchId);
        if (parents.isEmpty()) {
            throw corrupt(workbenchId, "owning workbench is missing", null);
        }
        ScopeParentRow parent = parents.get(0);
        return load(workbenchId, parent.workspaceRoot,
                parent.primaryRepositoryKey, parent.scopeHash);
    }

    RepositoryScope load(String workbenchId, String workspaceRoot,
                         String primaryRepositoryKey, String storedScopeHash) {
        List<ScopeRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workbench_repository_scope "
                        + "WHERE workbench_id=? ORDER BY repository_key",
                this::read, workbenchId);
        if (rows.isEmpty()) {
            throw corrupt(workbenchId, "repository scope has no repositories", null);
        }
        List<String> keys = new ArrayList<String>();
        List<ResolvedRepository> repositories = new ArrayList<ResolvedRepository>();
        int primaryCount = 0;
        String storedPrimary = null;
        try {
            for (ScopeRow row : rows) {
                if (!workbenchId.equals(row.workbenchId)) {
                    throw new IllegalArgumentException("repository row belongs to another workbench");
                }
                ResolvedRepository repository = ResolvedRepository.fromVerifiedFacts(
                        row.repositoryKey, row.repositoryRoot, row.rootFingerprint, false);
                if (!repository.getRelativePath().equals(row.relativePath)) {
                    throw new IllegalArgumentException(
                            "repository relative path does not match its canonical key");
                }
                if (row.primaryRepository) {
                    primaryCount++;
                    storedPrimary = row.repositoryKey;
                }
                keys.add(row.repositoryKey);
                repositories.add(repository);
            }
            if (primaryCount != 1 || !primaryRepositoryKey.equals(storedPrimary)) {
                throw new IllegalArgumentException(
                        "repository scope must contain the stored unique primary repository");
            }
            RepositoryScope scope = RepositoryScope.create(
                    workspaceRoot,
                    RepositorySelection.of(primaryRepositoryKey, keys),
                    repositories, rows.size());
            if (!scope.getScopeHash().equals(storedScopeHash)) {
                throw new IllegalArgumentException(
                        "repository scope hash does not match restored facts");
            }
            return scope;
        } catch (IllegalArgumentException ex) {
            throw corrupt(workbenchId, ex.getMessage(), ex);
        }
    }

    private ScopeRow read(ResultSet rs, int rowNumber) throws SQLException {
        return new ScopeRow(
                rs.getString("workbench_id"), rs.getString("repository_key"),
                rs.getString("relative_path"), rs.getString("repository_root"),
                rs.getString("root_fingerprint"),
                rs.getInt("primary_repository") != 0);
    }

    private IllegalStateException corrupt(String workbenchId, String detail,
                                          Throwable cause) {
        return new IllegalStateException(
                "corrupt workbench repository scope " + workbenchId + ": " + detail,
                cause);
    }

    private static final class ScopeRow {
        private final String workbenchId;
        private final String repositoryKey;
        private final String relativePath;
        private final String repositoryRoot;
        private final String rootFingerprint;
        private final boolean primaryRepository;

        private ScopeRow(String workbenchId, String repositoryKey,
                         String relativePath, String repositoryRoot,
                         String rootFingerprint, boolean primaryRepository) {
            this.workbenchId = workbenchId;
            this.repositoryKey = repositoryKey;
            this.relativePath = relativePath;
            this.repositoryRoot = repositoryRoot;
            this.rootFingerprint = rootFingerprint;
            this.primaryRepository = primaryRepository;
        }
    }

    private static final class ScopeParentRow {
        private final String workspaceRoot;
        private final String primaryRepositoryKey;
        private final String scopeHash;

        private ScopeParentRow(String workspaceRoot, String primaryRepositoryKey,
                               String scopeHash) {
            this.workspaceRoot = workspaceRoot;
            this.primaryRepositoryKey = primaryRepositoryKey;
            this.scopeHash = scopeHash;
        }
    }
}
