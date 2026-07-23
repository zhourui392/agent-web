package com.example.agentweb.app.worktree;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * updateBranch 单仓库结果。JSON 契约与原 Map 结构一致: skipped 仅跳过场景出现, 为 null 时不序列化。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorktreeRepoUpdateView(String name, boolean updated, Boolean skipped, String reason) {
}
