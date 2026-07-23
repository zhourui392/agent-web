package com.example.agentweb.app.worktree;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * switchBranch 单仓库结果。JSON 契约与原 Map 结构一致: reason 仅部分场景出现, 为 null 时不序列化。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorktreeRepoSwitchView(String name, String actualBranch, boolean created, String reason) {
}
