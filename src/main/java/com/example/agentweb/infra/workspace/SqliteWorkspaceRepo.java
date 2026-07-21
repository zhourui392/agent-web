package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workspace.PortLeaseStore;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceId;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import com.example.agentweb.domain.workspace.WorkspaceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * requirement_workspace + port_lease 表 SQLite 实现。租约分配是纯持久化竞态处理：
 * INSERT OR IGNORE 落空（唯一键已占）即重试下一个端口，无业务判断（detailed-design §2.5）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteWorkspaceRepo implements WorkspaceRepository, PortLeaseStore {

    private static final String COLUMNS = "id, requirement_id, repo_url, mirror_path, worktree_path, "
            + "branch, status, ttl_hours, last_active_at";

    private final JdbcTemplate jdbc;
    private final int portRangeStart;
    private final int portRangeEnd;
    private final RowMapper<RequirementWorkspace> rowMapper = (rs, rowNum) -> mapRow(rs);

    public SqliteWorkspaceRepo(JdbcTemplate jdbc, int portRangeStart, int portRangeEnd) {
        this.jdbc = jdbc;
        this.portRangeStart = portRangeStart;
        this.portRangeEnd = portRangeEnd;
    }

    @Override
    public void save(RequirementWorkspace workspace) {
        jdbc.update("INSERT OR REPLACE INTO requirement_workspace (" + COLUMNS
                        + ") VALUES (?,?,?,?,?,?,?,?,?)",
                workspace.getId().getValue(),
                workspace.getRequirementId(),
                workspace.getRepoUrl(),
                workspace.getMirrorPath(),
                workspace.getWorktreePath(),
                workspace.getBranch(),
                workspace.getStatus().name(),
                workspace.getTtlHours(),
                workspace.getLastActiveAt().toEpochMilli());
    }

    @Override
    public RequirementWorkspace findById(String workspaceId) {
        List<RequirementWorkspace> found = jdbc.query(
                "SELECT " + COLUMNS + " FROM requirement_workspace WHERE id=?", rowMapper, workspaceId);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public RequirementWorkspace findByRequirementId(String requirementId) {
        List<RequirementWorkspace> found = jdbc.query(
                "SELECT " + COLUMNS + " FROM requirement_workspace WHERE requirement_id=?",
                rowMapper, requirementId);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public List<RequirementWorkspace> findIdleBefore(Instant instant) {
        return jdbc.query("SELECT " + COLUMNS + " FROM requirement_workspace "
                        + "WHERE status != ? AND last_active_at < ? ORDER BY last_active_at",
                rowMapper, WorkspaceStatus.RELEASED.name(), instant.toEpochMilli());
    }

    @Override
    public int allocate(String workspaceId) {
        for (int port = portRangeStart; port <= portRangeEnd; port++) {
            int inserted = jdbc.update(
                    "INSERT OR IGNORE INTO port_lease (port, workspace_id, leased_at) VALUES (?,?,?)",
                    port, workspaceId, Instant.now().toEpochMilli());
            if (inserted == 1) {
                return port;
            }
        }
        throw new IllegalStateException(
                "port pool exhausted: " + portRangeStart + "-" + portRangeEnd);
    }

    @Override
    public void releaseAll(String workspaceId) {
        jdbc.update("DELETE FROM port_lease WHERE workspace_id=?", workspaceId);
    }

    @Override
    public List<Integer> portsOf(String workspaceId) {
        return jdbc.queryForList("SELECT port FROM port_lease WHERE workspace_id=? ORDER BY port",
                Integer.class, workspaceId);
    }

    private RequirementWorkspace mapRow(ResultSet rs) throws SQLException {
        return new RequirementWorkspace(
                new WorkspaceId(rs.getString("id")),
                rs.getString("requirement_id"),
                rs.getString("repo_url"),
                rs.getString("mirror_path"),
                rs.getString("worktree_path"),
                rs.getString("branch"),
                WorkspaceStatus.valueOf(rs.getString("status")),
                rs.getInt("ttl_hours"),
                Instant.ofEpochMilli(rs.getLong("last_active_at")));
    }
}
