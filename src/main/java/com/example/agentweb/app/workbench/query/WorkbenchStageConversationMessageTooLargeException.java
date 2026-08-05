package com.example.agentweb.app.workbench.query;

/**
 * 单条动态 Stage Conversation 消息超过安全响应边界。
 *
 * @author alex
 * @since 2026-08-05
 */
public final class WorkbenchStageConversationMessageTooLargeException
        extends RuntimeException {

    public WorkbenchStageConversationMessageTooLargeException() {
        super("stage conversation message exceeds the safe response limit");
    }
}
