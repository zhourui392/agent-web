package com.example.agentweb.domain.chatrun;

/**
 * 查询尚未迁入 ChatRun 表的兼容执行链路是否仍在运行。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@FunctionalInterface
public interface ChatExecutionActivityProbe {

    boolean isExecutionActive(String sessionId);

    static ChatExecutionActivityProbe inactive() {
        return sessionId -> false;
    }
}
