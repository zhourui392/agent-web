package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.PasswordHasher;
import com.example.agentweb.domain.auth.UserAccount;
import com.example.agentweb.domain.auth.UserAccountRepository;
import com.example.agentweb.domain.auth.UserPasswordService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公网启动门禁：首次对外提供服务前必须替换公开的管理员种子密码。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class PublicAccessBootstrap implements SmartInitializingSingleton {

    static final String INITIAL_ADMIN_PASSWORD_HASH =
            "$2b$12$DKOR1h0GGLppD.lpcl94N.TqktMUO3Bmh19O.moh9qhPzY/..ZdR.";

    private final PublicAccessProperties properties;
    private final UserAccountRepository repository;
    private final PasswordHasher passwordHasher;
    private final UserPasswordService passwordService;

    public PublicAccessBootstrap(PublicAccessProperties properties,
                                 UserAccountRepository repository,
                                 PasswordHasher passwordHasher,
                                 UserPasswordService passwordService) {
        this.properties = properties;
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.passwordService = passwordService;
    }

    @Override
    @Transactional
    public void afterSingletonsInstantiated() {
        if (!properties.isEnabled()) {
            return;
        }
        UserAccount admin = repository.findByUsername("admin")
                .orElseThrow(() -> new IllegalStateException("公网启动失败：数据库中不存在 admin 账户"));
        if (!INITIAL_ADMIN_PASSWORD_HASH.equals(admin.getPasswordHash())) {
            return;
        }
        String replacement = properties.getBootstrapAdminPassword();
        if (replacement == null || replacement.trim().isEmpty()) {
            throw new IllegalStateException(
                    "公网启动失败：请通过 AGENT_BOOTSTRAP_ADMIN_PASSWORD 设置新的管理员密码");
        }
        if (passwordHasher.matches(replacement, INITIAL_ADMIN_PASSWORD_HASH)) {
            throw new IllegalStateException("公网启动失败：管理员密码不能继续使用已公开的种子密码");
        }
        passwordService.changePassword(admin, replacement);
    }
}
