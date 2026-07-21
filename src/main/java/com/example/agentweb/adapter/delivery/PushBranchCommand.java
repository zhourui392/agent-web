package com.example.agentweb.adapter.delivery;

import lombok.Value;

import java.util.List;

/**
 * push 命令:worktree → origin req/* 分支。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class PushBranchCommand {

    /** 本地 worktree 路径 */
    String worktreePath;

    /** 远端仓库 URL(不含凭证,凭证走 env 注入) */
    String repoUrl;

    /** 目标分支(app 层已过 DeliveryPolicy.assertPushRefAllowed) */
    String branch;

    /** 交付回链 trailer 行;非空时 push 前追加交付标记空提交 */
    List<String> commitTrailers;

    /** 凭证 */
    ScmCredential credential;
}
