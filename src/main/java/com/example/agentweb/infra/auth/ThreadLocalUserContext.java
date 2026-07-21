package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link UserContext} 的请求级 ThreadLocal 实现，同时是当前登录用户的唯一存储归属。
 *
 * <p>{@link SessionAuthFilter} 校验通过后调 {@link #bind(LoginUser)} 写入，{@code finally} 中调
 * {@link #clear()} 清理（避免线程池复用导致用户串号）；领域侧经 {@link UserContext} 端口只读消费。
 * 取代了原先散落的静态 {@code SecurityContextHolder}，把 ThreadLocal 技术细节收敛到这一处。</p>
 *
 * @author zhourui(V33215020)
 */
@Component
public class ThreadLocalUserContext implements UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** Filter 校验通过后绑定当前请求的登录用户。 */
    public void bind(LoginUser user) {
        HOLDER.set(user);
    }

    /** Filter 在 finally 中清理，防止线程复用串号。 */
    public void clear() {
        HOLDER.remove();
    }

    @Override
    public Optional<LoginUser> currentUser() {
        return Optional.ofNullable(HOLDER.get());
    }
}
