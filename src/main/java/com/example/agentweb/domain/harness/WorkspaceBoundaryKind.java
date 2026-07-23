package com.example.agentweb.domain.harness;

/**
 * Codex 工作区配置扫描所使用的可信边界类型。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum WorkspaceBoundaryKind {
    /** 最近的 Git 仓库根。 */
    GIT_ROOT,
    /** 没有 Git 根时使用管理员批准根。 */
    APPROVED_ROOT
}
