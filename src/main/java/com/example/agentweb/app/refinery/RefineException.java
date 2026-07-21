package com.example.agentweb.app.refinery;

/**
 * refinery refine 流程失败的统一包装. 任何阶段抛错 (CLI / JSON / 字段校验) 都收敛到此.
 *
 * <p>由 {@link com.example.agentweb.app.refinery.RefineryAppService} 在 catch 后写
 * {@code chat_session_rag_state.last_error} 并累加 {@code retry_count}; 在 retry_count 达
 * {@code agent.refinery.poll.max-retries} 上限前, 下一轮调度仍会重试 (区别于 below-threshold:
 * 后者是有意决策, 不重试, 仅等会话有新消息后再评).</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public class RefineException extends RuntimeException {

    public RefineException(String message) {
        super(message);
    }

    public RefineException(String message, Throwable cause) {
        super(message, cause);
    }
}
