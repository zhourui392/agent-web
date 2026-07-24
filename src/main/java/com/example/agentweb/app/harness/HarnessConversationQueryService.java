package com.example.agentweb.app.harness;

import java.util.List;

/**
 * Harness 对话时间线 CQRS 查询端口。
 *
 * @author alex
 * @since 2026-07-24
 */
public interface HarnessConversationQueryService {

    /**
     * 查询一个 Run 的用户修订与 Runtime 主产物时间线。
     *
     * @param runId Run ID
     * @return 按时间排序的消息
     */
    List<HarnessConversationMessageView> list(String runId);
}
