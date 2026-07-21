package com.example.agentweb.adapter.delivery;

import java.util.List;

/**
 * SCM webhook 平台事件(密封层级,防腐边界产物)。
 *
 * <p>设计 §3.2 列了四类;§3.7 的 GitLab issue 标签接入同走 webhook,
 * 故补 IssueLabeled 一类,解析仍收口在 parseWebhook。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public sealed interface ScmWebhookEvent {

    /** pipeline 失败:ref 为 req/* 时可映射回需求,生成 fix-run 建议 */
    record PipelineFailed(String ref, String pipelineUrl, String status) implements ScmWebhookEvent {
    }

    /** MR 有新评论:生成 fix-run 建议 */
    record MrNoteAdded(long mrIid, String sourceBranch, String authorUsername,
                       String noteExcerpt, String noteUrl) implements ScmWebhookEvent {
    }

    /** MR 已合并:markDelivered [T10] */
    record MrMerged(long mrIid, String sourceBranch, String mergedByUsername) implements ScmWebhookEvent {
    }

    /** issue 打上接入标签:建需求(source=GITLAB_ISSUE) */
    record IssueLabeled(String issueUrl, String title, String description,
                        String authorUsername, List<String> labels) implements ScmWebhookEvent {
    }

    /** 不认识/不关心的事件 */
    record Unsupported(String eventType) implements ScmWebhookEvent {
    }
}
