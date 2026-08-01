package com.example.agentweb.app.workbench.query;

/**
 * 单条历史 Phase 消息超过安全响应边界。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class PhaseConversationMessageTooLargeException
        extends RuntimeException {

    public PhaseConversationMessageTooLargeException() {
        super("phase conversation message exceeds the safe response limit");
    }
}
