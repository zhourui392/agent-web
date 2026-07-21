package com.example.agentweb.domain.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CurrentUserProvider} 隔离决策单测：普通用户需过滤、无上下文(后台)放行、
 * 隔离总开关关闭时一律放行(全员互见)。用 lambda 喂 {@link UserContext}，零容器。
 *
 * @author zhourui(V33215020)
 */
class CurrentUserProviderTest {

    /** 隔离开关默认开(true)，等价于线上未配置时的安全默认。 */
    private CurrentUserProvider provider(String uid) {
        return provider(uid, true);
    }

    private CurrentUserProvider provider(String uid, boolean isolationEnabled) {
        UserContext userContext = () -> uid == null
                ? Optional.empty()
                : Optional.of(new LoginUser(uid, uid, null));
        return new CurrentUserProvider(userContext, isolationEnabled);
    }

    /** 普通用户：有当前用户 → 需过滤。 */
    @Test
    void normal_user_should_filter() {
        CurrentUserProvider p = provider("alice");

        assertEquals("alice", p.currentUserId());
        assertTrue(p.shouldFilter());
    }

    /** 无当前用户(后台线程/未登录) → currentUserId 为 null，不过滤(bypass)。 */
    @Test
    void no_current_user_should_bypass_filter() {
        CurrentUserProvider p = provider(null);

        assertNull(p.currentUserId());
        assertFalse(p.shouldFilter());
    }

    /** 隔离总开关关闭：即便有当前用户也不过滤(全员互见)。 */
    @Test
    void isolation_disabled_should_not_filter_even_with_user() {
        CurrentUserProvider p = provider("alice", false);

        assertEquals("alice", p.currentUserId());
        assertFalse(p.shouldFilter());
    }
}
