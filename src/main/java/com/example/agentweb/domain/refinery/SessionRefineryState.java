package com.example.agentweb.domain.refinery;

import lombok.Getter;
import java.time.Instant;
import java.util.Objects;

/**
 * refinery 调度器幂等记录. 每个 session 至多一行, 记录"上次评分跑到哪一条消息".
 *
 * <p>调度器扫表时按 {@code lastMessageAtSeen < chat_session.last_message_at} 判断
 * "有新消息需要再评一次"; 同 session 二次评分会生成新 chunk, 旧 chunk 不删, 按 TTL 自然衰减.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public final class SessionRefineryState {

    /**
     * {@code last_error} 的哨兵值: 评分低于阈值, 内容不值得沉淀. 与真·失败 (CLI/embedding 报错) 区分:
     * below-threshold 不重试 (有意决策), 真·失败在 retry_count 未达上限前下一轮重试.
     * 调度器 SQL 按此值精确匹配排除, 故提为共享常量, 避免魔法字符串两处漂移.
     */
    public static final String LAST_ERROR_BELOW_THRESHOLD = "score below threshold";

    @Getter
    private final String sessionId;
    @Getter
    private final Instant lastRefinedAt;
    @Getter
    private final Instant lastMessageAtSeen;
    @Getter
    private final String lastChunkId;
    @Getter
    private final String lastError;
    @Getter
    private final int retryCount;

    public SessionRefineryState(String sessionId,
                               Instant lastRefinedAt,
                               Instant lastMessageAtSeen,
                               String lastChunkId,
                               String lastError,
                               int retryCount) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.lastRefinedAt = Objects.requireNonNull(lastRefinedAt, "lastRefinedAt");
        this.lastMessageAtSeen = Objects.requireNonNull(lastMessageAtSeen, "lastMessageAtSeen");
        this.lastChunkId = lastChunkId;
        this.lastError = lastError;
        this.retryCount = retryCount;
    }
}
