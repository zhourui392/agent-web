package com.example.agentweb.infra.workspace;

import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceId;
import com.example.agentweb.domain.workspace.WorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区仓储轻集成（@TempDir + SQLiteDataSource，不起 Spring）：save/find 回环、
 * findIdleBefore 边界、租约分配冲突重试、按 workspace_id 整体释放（verification-plan M1 行 5）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteWorkspaceRepoTest {

    @TempDir
    Path tempDir;

    private SqliteWorkspaceRepo repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("workspace-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        WorkspaceTestSchema.create(jdbc);
        repo = new SqliteWorkspaceRepo(jdbc, 42000, 42001);
    }

    private RequirementWorkspace workspaceOf(String requirementId, WorkspaceStatus status, Instant lastActive) {
        return new RequirementWorkspace(WorkspaceId.newId(requirementId), requirementId,
                "http://git/repo.git", "D:/ws/mirrors/repo.git", "D:/ws/worktrees/" + requirementId,
                "req/" + requirementId, status, 72, lastActive);
    }

    // ---- 聚合 save/find ----

    @Test
    public void save_then_findById_should_round_trip_all_fields() {
        RequirementWorkspace workspace = workspaceOf("R2607040001", WorkspaceStatus.READY,
                Instant.parse("2026-07-04T00:00:00Z"));

        repo.save(workspace);
        RequirementWorkspace loaded = repo.findById("W2607040001");

        assertNotNull(loaded);
        assertEquals("R2607040001", loaded.getRequirementId());
        assertEquals("http://git/repo.git", loaded.getRepoUrl());
        assertEquals("D:/ws/mirrors/repo.git", loaded.getMirrorPath());
        assertEquals("D:/ws/worktrees/R2607040001", loaded.getWorktreePath());
        assertEquals("req/R2607040001", loaded.getBranch());
        assertEquals(WorkspaceStatus.READY, loaded.getStatus());
        assertEquals(72, loaded.getTtlHours());
        assertEquals(Instant.parse("2026-07-04T00:00:00Z"), loaded.getLastActiveAt());
    }

    @Test
    public void save_should_upsert_on_same_id() {
        RequirementWorkspace workspace = workspaceOf("R2607040001", WorkspaceStatus.READY, Instant.now());
        repo.save(workspace);

        workspace.markInUse();
        repo.save(workspace);

        assertEquals(WorkspaceStatus.IN_USE, repo.findById("W2607040001").getStatus());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM requirement_workspace", Integer.class);
        assertEquals(1, count);
    }

    @Test
    public void find_miss_should_return_null() {
        assertNull(repo.findById("W-none"));
        assertNull(repo.findByRequirementId("R-none"));
    }

    @Test
    public void findByRequirementId_should_locate_workspace() {
        repo.save(workspaceOf("R2607040001", WorkspaceStatus.READY, Instant.now()));

        assertEquals("W2607040001", repo.findByRequirementId("R2607040001").getId().getValue());
    }

    @Test
    public void findIdleBefore_should_exclude_released_and_boundary() {
        Instant cutoff = Instant.parse("2026-07-04T00:00:00Z");
        repo.save(workspaceOf("R2607040001", WorkspaceStatus.READY, cutoff.minusSeconds(1)));
        repo.save(workspaceOf("R2607040002", WorkspaceStatus.RELEASED, cutoff.minusSeconds(1)));
        repo.save(workspaceOf("R2607040003", WorkspaceStatus.READY, cutoff));
        repo.save(workspaceOf("R2607040004", WorkspaceStatus.IN_USE, cutoff.plusSeconds(10)));

        List<RequirementWorkspace> idle = repo.findIdleBefore(cutoff);

        assertEquals(1, idle.size());
        assertEquals("W2607040001", idle.get(0).getId().getValue());
    }

    // ---- 端口租约 ----

    @Test
    public void allocate_should_retry_next_port_on_conflict() {
        assertEquals(42000, repo.allocate("W-a"));
        assertEquals(42001, repo.allocate("W-b"));
    }

    @Test
    public void allocate_should_throw_when_pool_exhausted() {
        repo.allocate("W-a");
        repo.allocate("W-b");

        assertThrows(IllegalStateException.class, () -> repo.allocate("W-c"));
    }

    @Test
    public void releaseAll_should_remove_only_that_workspace_leases() {
        int portA = repo.allocate("W-a");
        repo.allocate("W-b");

        repo.releaseAll("W-a");

        assertTrue(repo.portsOf("W-a").isEmpty());
        assertEquals(List.of(42001), repo.portsOf("W-b"));
        assertEquals(portA, repo.allocate("W-c"));
    }
}
