package com.example.agentweb.adapter.workspace;

import lombok.Value;

/**
 * 工作区供给结果。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class ProvisionedWorkspace {

    String mirrorPath;
    String worktreePath;

    /** worktree 基点提交（审计与后续 dirty 检测的 rev-list 基准）。 */
    String resolvedBaseCommit;
}
