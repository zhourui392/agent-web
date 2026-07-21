package com.example.agentweb.adapter.delivery;

import lombok.Value;

/**
 * 创建草稿 MR 命令。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class CreateMrCommand {

    /** 远端仓库 URL(用于解析 GitLab project 路径) */
    String repoUrl;

    /** 源分支(req/*) */
    String sourceBranch;

    /** 目标分支(仓库主干) */
    String targetBranch;

    /** MR 标题(app 层已过 DeliveryPolicy.draftTitle) */
    String title;

    /** MR 描述(含需求回链) */
    String description;

    /** 凭证 */
    ScmCredential credential;
}
