package com.example.agentweb.adapter.delivery;

import com.example.agentweb.domain.delivery.MergeRequestRef;

/**
 * SCM 交付端口:push 分支、创建/查询 MR、webhook 防腐解析。
 *
 * <p>签名只用平台类型;凭证由 app 层解析后经命令对象传入,实现侧只做协议适配。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface ScmGateway {

    /** worktree → origin,显式 refspec 单分支推送(禁裸 push,与禁 --mirror 红线互为双保险) */
    void pushBranch(PushBranchCommand cmd);

    /** 创建草稿 MR(标题 draft 前缀由 app 层 DeliveryPolicy 保证) */
    MergeRequestRef createDraftMergeRequest(CreateMrCommand cmd);

    /** 查询 MR 当前状态 */
    MergeRequestRef fetchMergeRequest(String repoUrl, long mrIid);

    /** 防腐:SCM webhook JSON → 平台事件,不认识的类型返回 Unsupported */
    ScmWebhookEvent parseWebhook(WebhookEnvelope envelope);
}
