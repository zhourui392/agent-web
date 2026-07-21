package com.example.agentweb.domain.workspace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区聚合：assertReleasable 四象限（dirty×force）、状态迁移合法性、TTL 判据、
 * req/&lt;requirementId&gt; 分支构造不变量（verification-plan M1 行 1）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementWorkspaceTest {

    private static final String REQ_ID = "R2607040001";

    private RequirementWorkspace newWorkspace() {
        return RequirementWorkspace.create(REQ_ID, "http://git/repo.git",
                "D:/ws/mirrors/repo.git", "D:/ws/worktrees/" + REQ_ID, 72);
    }

    // ---- 创建与构造不变量 ----

    @Test
    public void create_should_start_provisioning_with_req_branch_and_w_prefixed_id() {
        RequirementWorkspace workspace = newWorkspace();

        assertEquals(WorkspaceStatus.PROVISIONING, workspace.getStatus());
        assertEquals("req/" + REQ_ID, workspace.getBranch());
        assertEquals("W2607040001", workspace.getId().getValue());
        assertEquals(REQ_ID, workspace.getRequirementId());
        assertNotNull(workspace.getLastActiveAt());
    }

    @Test
    public void constructor_should_reject_branch_not_matching_requirement() {
        assertThrows(IllegalArgumentException.class, () -> new RequirementWorkspace(
                WorkspaceId.newId(REQ_ID), REQ_ID, "http://git/repo.git", "m", "w",
                "req/R9999999999", WorkspaceStatus.READY, 72, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new RequirementWorkspace(
                WorkspaceId.newId(REQ_ID), REQ_ID, "http://git/repo.git", "m", "w",
                "feature/other", WorkspaceStatus.READY, 72, Instant.now()));
    }

    @Test
    public void constructor_should_reject_blank_required_fields_and_non_positive_ttl() {
        assertThrows(IllegalArgumentException.class,
                () -> RequirementWorkspace.create(" ", "http://git/repo.git", "m", "w", 72));
        assertThrows(IllegalArgumentException.class,
                () -> RequirementWorkspace.create(REQ_ID, " ", "m", "w", 72));
        assertThrows(IllegalArgumentException.class,
                () -> RequirementWorkspace.create(REQ_ID, "http://git/repo.git", " ", "w", 72));
        assertThrows(IllegalArgumentException.class,
                () -> RequirementWorkspace.create(REQ_ID, "http://git/repo.git", "m", " ", 72));
        assertThrows(IllegalArgumentException.class,
                () -> RequirementWorkspace.create(REQ_ID, "http://git/repo.git", "m", "w", 0));
    }

    // ---- assertReleasable 四象限 ----

    @Test
    public void releasable_clean_should_pass_regardless_of_force() {
        RequirementWorkspace workspace = newWorkspace();

        assertDoesNotThrow(() -> workspace.assertReleasable(DirtyReport.clean(), false));
        assertDoesNotThrow(() -> workspace.assertReleasable(DirtyReport.clean(), true));
    }

    @Test
    public void releasable_dirty_without_force_should_throw_with_report() {
        RequirementWorkspace workspace = newWorkspace();
        DirtyReport dirty = new DirtyReport(List.of("src/a.java"), 2);

        WorkspaceDirtyException exception = assertThrows(WorkspaceDirtyException.class,
                () -> workspace.assertReleasable(dirty, false));
        assertEquals(workspace.getId().getValue(), exception.getWorkspaceId());
        assertEquals(dirty, exception.getReport());
    }

    @Test
    public void releasable_dirty_with_force_should_pass() {
        RequirementWorkspace workspace = newWorkspace();

        assertDoesNotThrow(() -> workspace.assertReleasable(
                new DirtyReport(List.of("src/a.java"), 0), true));
    }

    // ---- 状态迁移 ----

    @Test
    public void status_should_walk_provisioning_ready_inuse_and_back() {
        RequirementWorkspace workspace = newWorkspace();

        workspace.markReady();
        assertEquals(WorkspaceStatus.READY, workspace.getStatus());
        workspace.markInUse();
        assertEquals(WorkspaceStatus.IN_USE, workspace.getStatus());
        workspace.markReady();
        assertEquals(WorkspaceStatus.READY, workspace.getStatus());
        workspace.markReleased();
        assertEquals(WorkspaceStatus.RELEASED, workspace.getStatus());
    }

    @Test
    public void mark_in_use_before_ready_should_be_rejected() {
        RequirementWorkspace workspace = newWorkspace();

        assertThrows(IllegalStateException.class, workspace::markInUse);
    }

    @Test
    public void released_should_be_terminal() {
        RequirementWorkspace workspace = newWorkspace();
        workspace.markReady();
        workspace.markReleased();

        assertThrows(IllegalStateException.class, workspace::markReady);
        assertThrows(IllegalStateException.class, workspace::markInUse);
        assertThrows(IllegalStateException.class, workspace::markReleased);
    }

    @Test
    public void is_reusable_should_be_false_only_after_release() {
        RequirementWorkspace workspace = newWorkspace();
        assertTrue(workspace.isReusable());

        workspace.markReady();
        workspace.markInUse();
        assertTrue(workspace.isReusable());

        workspace.markReleased();
        assertFalse(workspace.isReusable());
    }

    @Test
    public void branch_for_should_be_single_source_of_naming() {
        assertEquals("req/" + REQ_ID, RequirementWorkspace.branchFor(REQ_ID));
        assertEquals(RequirementWorkspace.branchFor(REQ_ID), newWorkspace().getBranch());
    }

    // ---- TTL 判据与 touch ----

    @Test
    public void is_expired_should_be_true_only_strictly_after_ttl_window() {
        Instant lastActive = Instant.parse("2026-07-04T00:00:00Z");
        RequirementWorkspace workspace = new RequirementWorkspace(
                WorkspaceId.newId(REQ_ID), REQ_ID, "http://git/repo.git", "m", "w",
                "req/" + REQ_ID, WorkspaceStatus.READY, 72, lastActive);

        Instant atBoundary = lastActive.plus(72, ChronoUnit.HOURS);
        assertFalse(workspace.isExpired(atBoundary));
        assertTrue(workspace.isExpired(atBoundary.plusMillis(1)));
        assertFalse(workspace.isExpired(lastActive));
    }

    @Test
    public void touch_should_refresh_last_active_at() {
        Instant lastActive = Instant.parse("2026-07-04T00:00:00Z");
        RequirementWorkspace workspace = new RequirementWorkspace(
                WorkspaceId.newId(REQ_ID), REQ_ID, "http://git/repo.git", "m", "w",
                "req/" + REQ_ID, WorkspaceStatus.READY, 72, lastActive);

        workspace.touch();

        assertTrue(workspace.getLastActiveAt().isAfter(lastActive));
    }

    // ---- DirtyReport VO ----

    @Test
    public void dirty_report_should_be_dirty_when_any_dimension_non_empty() {
        assertFalse(DirtyReport.clean().isDirty());
        assertFalse(new DirtyReport(List.of(), 0).isDirty());
        assertTrue(new DirtyReport(List.of("a.txt"), 0).isDirty());
        assertTrue(new DirtyReport(List.of(), 1).isDirty());
    }

    @Test
    public void dirty_report_should_tolerate_null_file_list() {
        DirtyReport report = new DirtyReport(null, 0);

        assertFalse(report.isDirty());
        assertTrue(report.getUncommittedFiles().isEmpty());
    }

    // ---- WorkspaceId VO ----

    @Test
    public void workspace_id_should_replace_requirement_prefix_with_w() {
        assertEquals("W2607040001", WorkspaceId.newId("R2607040001").getValue());
        assertThrows(IllegalArgumentException.class, () -> WorkspaceId.newId(" "));
    }
}
