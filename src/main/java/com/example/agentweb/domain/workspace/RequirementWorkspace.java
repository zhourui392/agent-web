package com.example.agentweb.domain.workspace;

import lombok.Getter;

import java.time.Duration;
import java.time.Instant;

/**
 * 需求工作区聚合根：git worktree 隔离单元的生命周期与释放不变量（detailed-design §2.2）。
 * 端口租约是全局基础设施资源，不在聚合内（§2.5）；跨聚合只持 requirementId 引用。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Getter
public class RequirementWorkspace {

    private static final String BRANCH_PREFIX = "req/";

    private final WorkspaceId id;
    private final String requirementId;
    private final String repoUrl;
    private final String mirrorPath;
    private final String worktreePath;

    /** 必须 req/&lt;requirementId&gt;，构造期校验（平台分支命名空间，交付侧 push 白名单同源）。 */
    private final String branch;

    private WorkspaceStatus status;
    private final int ttlHours;
    private Instant lastActiveAt;

    public static RequirementWorkspace create(String requirementId, String repoUrl,
                                              String mirrorPath, String worktreePath, int ttlHours) {
        String trimmedRequirementId = requirementId == null ? "" : requirementId.trim();
        return new RequirementWorkspace(WorkspaceId.newId(trimmedRequirementId), trimmedRequirementId,
                repoUrl, mirrorPath, worktreePath, BRANCH_PREFIX + trimmedRequirementId,
                WorkspaceStatus.PROVISIONING, ttlHours, Instant.now());
    }

    /** 全量重建构造器供 Repository 使用，构造期不变量在此收口。 */
    public RequirementWorkspace(WorkspaceId id, String requirementId, String repoUrl,
                                String mirrorPath, String worktreePath, String branch,
                                WorkspaceStatus status, int ttlHours, Instant lastActiveAt) {
        if (id == null) {
            throw new IllegalArgumentException("workspace id required");
        }
        requireText(requirementId, "requirementId required");
        requireText(repoUrl, "repoUrl required");
        requireText(mirrorPath, "mirrorPath required");
        requireText(worktreePath, "worktreePath required");
        if (status == null) {
            throw new IllegalArgumentException("status required");
        }
        if (ttlHours <= 0) {
            throw new IllegalArgumentException("ttlHours must be positive");
        }
        String expectedBranch = BRANCH_PREFIX + requirementId.trim();
        if (!expectedBranch.equals(branch)) {
            throw new IllegalArgumentException(
                    "branch must be " + expectedBranch + " but was " + branch);
        }
        this.id = id;
        this.requirementId = requirementId.trim();
        this.repoUrl = repoUrl.trim();
        this.mirrorPath = mirrorPath.trim();
        this.worktreePath = worktreePath.trim();
        this.branch = branch;
        this.status = status;
        this.ttlHours = ttlHours;
        this.lastActiveAt = lastActiveAt == null ? Instant.now() : lastActiveAt;
    }

    /** 平台分支命名空间唯一事实源，编排侧禁止自行拼接。 */
    public static String branchFor(String requirementId) {
        return BRANCH_PREFIX + requirementId;
    }

    /** 未释放即可复用（provision 幂等入口的判据，语义收聚合，app 不做状态比对）。 */
    public boolean isReusable() {
        return status != WorkspaceStatus.RELEASED;
    }

    /** 不变量：dirty 且未 force 时拒绝释放；SUSPENDED 需求的保留判定在 {@link WorkspaceRetentionPolicy}。 */
    public void assertReleasable(DirtyReport report, boolean force) {
        if (report.isDirty() && !force) {
            throw new WorkspaceDirtyException(id.getValue(), report);
        }
    }

    /** PROVISIONING 供给完成 / IN_USE run 结束，回到可用态。 */
    public void markReady() {
        assertTransition(status == WorkspaceStatus.PROVISIONING || status == WorkspaceStatus.IN_USE,
                WorkspaceStatus.READY);
        this.status = WorkspaceStatus.READY;
        touch();
    }

    public void markInUse() {
        assertTransition(status == WorkspaceStatus.READY, WorkspaceStatus.IN_USE);
        this.status = WorkspaceStatus.IN_USE;
        touch();
    }

    public void markReleased() {
        assertTransition(status != WorkspaceStatus.RELEASED, WorkspaceStatus.RELEASED);
        this.status = WorkspaceStatus.RELEASED;
        touch();
    }

    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    /** TTL 判据：严格超过 lastActiveAt + ttlHours 才算过期（边界时刻不过期）。 */
    public boolean isExpired(Instant now) {
        return now.isAfter(lastActiveAt.plus(Duration.ofHours(ttlHours)));
    }

    private void assertTransition(boolean allowed, WorkspaceStatus target) {
        if (!allowed) {
            throw new IllegalStateException(
                    "illegal workspace transition: " + status + " -> " + target + " (" + id.getValue() + ")");
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}
