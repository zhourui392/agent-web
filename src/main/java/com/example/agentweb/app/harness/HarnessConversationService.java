package com.example.agentweb.app.harness;

/**
 * Harness 阶段对话用例。
 *
 * @author alex
 * @since 2026-07-24
 */
public interface HarnessConversationService {

    /**
     * 受理一条阶段修改指令并启动对应 Runtime。
     *
     * @param command 阶段对话命令
     * @return Attempt 与 Runtime 标识
     */
    HarnessConversationTurnResult send(StartHarnessConversationCommand command);
}
