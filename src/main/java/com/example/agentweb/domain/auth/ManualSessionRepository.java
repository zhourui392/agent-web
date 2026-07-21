package com.example.agentweb.domain.auth;

import java.time.Instant;
import java.util.Optional;

/**
 * {@link ManualSession} 的持久化端口 (Repository,写侧)。实现落 infra,domain 仅依赖此接口。
 *
 * <p>方法集刻意保持小:聚合根 lifecycle 三件套 + 过期清理。读模型若有页面查询需求请另开
 * {@code ManualSessionQueryService},不要塞进此接口 (CQRS 边界)。</p>
 *
 * @author zhourui(V33215020)
 */
public interface ManualSessionRepository {

    /**
     * 新建或覆盖。sessionId 是主键,upsert 语义。
     */
    void save(ManualSession session);

    /**
     * 按 sessionId 取;未命中返回 {@link Optional#empty()}。是否过期由调用方用 {@link ManualSession#isExpired}
     * 判断 (Repo 只管"存在",不管"有效"),便于上层在 hit 但过期时一并触发清理。
     */
    Optional<ManualSession> findById(String sessionId);

    /**
     * 登出 / 主动作废时调用。不存在的 sessionId 静默忽略。
     */
    void deleteById(String sessionId);

    /**
     * 批量清理 expiresAt < threshold 的会话。返回受影响行数,供后台 tick 监控。
     */
    int deleteExpiredBefore(Instant threshold);
}
