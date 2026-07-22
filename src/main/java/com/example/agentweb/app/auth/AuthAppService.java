package com.example.agentweb.app.auth;

import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.ManualSession;
import com.example.agentweb.domain.auth.ManualSessionAuthenticator;
import com.example.agentweb.domain.auth.ManualSessionFactory;
import com.example.agentweb.domain.auth.ManualSessionRepository;
import com.example.agentweb.domain.auth.UserAuthenticator;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 本地登录应用服务，负责编排登录会话的创建、认证与注销。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
@Service
public class AuthAppService {

    private final UserAuthenticator userAuthenticator;
    private final ManualSessionFactory sessionFactory;
    private final ManualSessionAuthenticator sessionAuthenticator;
    private final ManualSessionRepository sessionRepository;

    public AuthAppService(UserAuthenticator userAuthenticator,
                          ManualSessionFactory sessionFactory,
                          ManualSessionAuthenticator sessionAuthenticator,
                          ManualSessionRepository sessionRepository) {
        this.userAuthenticator = userAuthenticator;
        this.sessionFactory = sessionFactory;
        this.sessionAuthenticator = sessionAuthenticator;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 创建本地登录会话并持久化。
     *
     * @param employeeId 工号
     * @param userName 用户名
     * @return 新建的登录会话
     */
    public Optional<ManualSession> login(String username, String password) {
        return userAuthenticator.authenticate(username, password)
                .map(sessionFactory::create)
                .map(this::saveAndReturn);
    }

    private ManualSession saveAndReturn(ManualSession session) {
        sessionRepository.save(session);
        return session;
    }

    /**
     * 删除本地登录会话。
     *
     * @param sessionToken 会话令牌，可为空
     */
    public void logout(String sessionToken) {
        if (sessionToken != null) {
            sessionRepository.deleteById(sessionToken);
        }
    }

    /**
     * 解析当前本地登录用户。
     *
     * @param sessionToken 会话令牌
     * @return 有效登录用户，无效或过期时返回空
     */
    public Optional<LoginUser> resolveUser(String sessionToken) {
        return sessionAuthenticator.authenticate(sessionToken);
    }
}
