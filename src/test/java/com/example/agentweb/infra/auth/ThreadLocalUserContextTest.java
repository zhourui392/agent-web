package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ThreadLocalUserContext} 单测：bind→currentUser→clear 生命周期，以及线程隔离
 * (静态 ThreadLocal 不跨线程串号)。
 *
 * @author zhourui(V33215020)
 */
class ThreadLocalUserContextTest {

    private final ThreadLocalUserContext ctx = new ThreadLocalUserContext();

    @AfterEach
    void tearDown() {
        ctx.clear();
    }

    /** bind 后当前线程可读，clear 后回到空。 */
    @Test
    void bind_then_read_then_clear() {
        ctx.clear();
        assertFalse(ctx.currentUser().isPresent(), "初始应为空");

        ctx.bind(new LoginUser("Q600096", "周锐", null));
        assertTrue(ctx.currentUser().isPresent());
        assertEquals("Q600096", ctx.currentUserId());
        assertEquals("周锐", ctx.currentUserName());

        ctx.clear();
        assertFalse(ctx.currentUser().isPresent(), "clear 后应为空");
    }

    /** 主线程 bind 的用户不应被另一个线程看到(请求级隔离)。 */
    @Test
    void other_thread_should_not_see_bound_user() throws InterruptedException {
        ctx.bind(new LoginUser("Q600096", "周锐", null));

        AtomicBoolean seenInOtherThread = new AtomicBoolean(true);
        Thread t = new Thread(() -> seenInOtherThread.set(ctx.currentUser().isPresent()));
        t.start();
        t.join();

        assertFalse(seenInOtherThread.get(), "另一线程不应看到主线程绑定的用户");
    }
}
