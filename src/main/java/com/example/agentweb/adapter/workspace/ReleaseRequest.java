package com.example.agentweb.adapter.workspace;

import lombok.Value;

/**
 * 工作区释放请求。removeBranch 按回收矩阵由编排决定：DELIVERED / clean-ARCHIVED 才删分支
 * （master-plan §6.5），TTL 清理一律保留分支。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class ReleaseRequest {

    String mirrorPath;
    String worktreePath;
    String branch;
    boolean force;
    boolean removeBranch;
}
