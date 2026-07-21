package com.example.agentweb.adapter.workspace;

import com.example.agentweb.domain.workspace.DirtyReport;

/**
 * 工作区供给端口：git worktree（Stage1）与容器（Stage2/M3）同端口换实现，
 * 签名只用平台类型（detailed-design §2.3）。仅 app 层消费（ArchUnit A2）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface WorkspaceProvisioner {

    /** 幂等：mirror 已存在则 fetch --prune 更新；worktree 已存在且分支匹配则直接返回。 */
    ProvisionedWorkspace provision(ProvisionRequest request);

    /** 调用前 app 已过聚合 assertReleasable；force 透传给 git worktree remove --force。 */
    void release(ReleaseRequest request);

    DirtyReport detectDirty(String worktreePath);
}
