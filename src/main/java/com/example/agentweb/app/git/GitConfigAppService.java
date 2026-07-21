package com.example.agentweb.app.git;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.git.GitConfigPolicy;
import com.example.agentweb.domain.git.GitIdentity;
import com.example.agentweb.domain.git.UserGitConfig;
import com.example.agentweb.domain.git.UserGitConfigRepository;
import com.example.agentweb.infra.git.GitCredentialCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 用户 git 配置应用服务：读当前用户配置 + 保存。仅编排 + 授权闸门，业务不变量在 domain。
 *
 * <p>无登录上下文时拒写（{@link GitConfigPolicy#isSystemContext} → {@link IllegalStateException} → 409）。
 * 凭证密码加密后落库，明文绝不入库 / 不回显。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Service
@Slf4j
public class GitConfigAppService {

    private final UserGitConfigRepository repository;
    private final GitConfigPolicy policy;
    private final CurrentUserProvider currentUserProvider;
    private final GitCredentialCipher cipher;

    public GitConfigAppService(UserGitConfigRepository repository,
                               GitConfigPolicy policy,
                               CurrentUserProvider currentUserProvider,
                               GitCredentialCipher cipher) {
        this.repository = repository;
        this.policy = policy;
        this.currentUserProvider = currentUserProvider;
        this.cipher = cipher;
    }

    /**
     * 当前用户配置视图。无登录上下文 → 只读空视图；登录用户 → 已存配置或可编辑空视图。
     *
     * @return 视图
     */
    public GitConfigView getForCurrentUser() {
        String userId = currentUserProvider.currentUserId();
        if (policy.isSystemContext(userId)) {
            return GitConfigView.readOnly();
        }
        UserGitConfig config = repository.findByUserId(userId).orElse(null);
        if (config == null || config.getIdentity() == null) {
            return GitConfigView.editableEmpty();
        }
        GitIdentity identity = config.getIdentity();
        return new GitConfigView(identity.getName(), identity.getEmail(), config.hasCredential(), false);
    }

    /**
     * 保存当前用户配置。
     *
     * @param name        提交者姓名
     * @param email       提交者邮箱
     * @param credUsername push 用户名（可空）
     * @param credPassword push 密码 / token（可空；空表示不改既有凭证）
     * @throws IllegalStateException    系统默认用户拒写 / 设凭证但 cipher 未启用
     * @throws IllegalArgumentException 身份非法 / 设密码但用户名空
     */
    public void save(String name, String email, String credUsername, String credPassword) {
        String userId = currentUserProvider.currentUserId();
        if (policy.isSystemContext(userId)) {
            throw new IllegalStateException("系统默认用户使用机器默认 git 配置，不可修改");
        }
        GitIdentity identity = GitIdentity.of(name, email);
        Instant now = Instant.now();
        UserGitConfig config = repository.findByUserId(userId)
                .orElseGet(() -> UserGitConfig.create(userId, identity, now));
        config.updateIdentity(identity, now);
        applyCredential(config, credUsername, credPassword, now);
        repository.save(config);
        log.info("git-config-saved userId={} credentialConfigured={}", userId, config.hasCredential());
    }

    /** 仅当本次带了新密码才动凭证：空密码视为「身份-only 保存」，保留既有凭证。 */
    private void applyCredential(UserGitConfig config, String credUsername, String credPassword, Instant now) {
        boolean hasNewPassword = credPassword != null && !credPassword.trim().isEmpty();
        if (!hasNewPassword) {
            return;
        }
        if (!cipher.isEnabled()) {
            throw new IllegalStateException("push 凭证存储未启用：" + GitCredentialCipher.ENV_KEY + " 未配置");
        }
        if (credUsername == null || credUsername.trim().isEmpty()) {
            throw new IllegalArgumentException("git 凭证用户名不能为空");
        }
        config.updateCredential(credUsername.trim(), cipher.encrypt(credPassword), now);
    }
}
