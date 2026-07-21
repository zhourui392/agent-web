package com.example.agentweb.infra.delivery;

import com.example.agentweb.adapter.delivery.ScmCredential;
import com.example.agentweb.adapter.delivery.ScmCredentialStore;
import com.example.agentweb.domain.git.UserGitConfig;
import com.example.agentweb.domain.git.UserGitConfigRepository;
import com.example.agentweb.infra.git.GitCredentialCipher;
import com.example.agentweb.infra.setting.AppSettingRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.Function;

/**
 * GitLab 凭证读取实现:个人凭证走 user_git_config 密文解密,默认账号 env 优先、app_setting 密文回落。
 *
 * <p>密钥触达(解密/读 env)全部收在本类,顺序规则(个人 → 默认 → 拒绝)由 app 层编排。
 * 解密失败一律降级 empty 并 log.warn(不带密文与明文)。装配由 @Configuration 负责,不带 Spring 注解。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class GitLabCredentialStore implements ScmCredentialStore {

    /** 默认账号 token 的环境变量名(明文注入,优先级最高) */
    static final String ENV_DEFAULT_TOKEN = "AGENT_GITLAB_DEFAULT_TOKEN";

    /** 默认账号 token 的 app_setting 键(存 GitCredentialCipher 密文) */
    static final String SETTING_DEFAULT_TOKEN = "delivery.gitlab.default-token";

    private final UserGitConfigRepository userGitConfigRepo;
    private final GitCredentialCipher cipher;
    private final AppSettingRepository appSettings;
    private final String defaultUsername;
    private final Function<String, String> envReader;

    /**
     * 生产构造:env 读取走 {@link System#getenv(String)}。
     *
     * @param userGitConfigRepo 用户 git 配置仓储
     * @param cipher            凭证加解密
     * @param appSettings       运行时配置
     * @param defaultUsername   默认账号用户名(空白视为未配置默认账号)
     */
    public GitLabCredentialStore(UserGitConfigRepository userGitConfigRepo, GitCredentialCipher cipher,
                                 AppSettingRepository appSettings, String defaultUsername) {
        this(userGitConfigRepo, cipher, appSettings, defaultUsername, System::getenv);
    }

    /**
     * 测试构造:env 读取可注入,避免依赖进程环境变量。
     *
     * @param userGitConfigRepo 用户 git 配置仓储
     * @param cipher            凭证加解密
     * @param appSettings       运行时配置
     * @param defaultUsername   默认账号用户名
     * @param envReader         环境变量读取函数
     */
    public GitLabCredentialStore(UserGitConfigRepository userGitConfigRepo, GitCredentialCipher cipher,
                                 AppSettingRepository appSettings, String defaultUsername,
                                 Function<String, String> envReader) {
        this.userGitConfigRepo = userGitConfigRepo;
        this.cipher = cipher;
        this.appSettings = appSettings;
        this.defaultUsername = defaultUsername;
        this.envReader = envReader;
    }

    /**
     * 用户个人 GitLab 凭证:未配置 / 未启用加密 / 解密失败均返回 empty。
     *
     * @param userId 用户工号
     * @return 个人凭证
     */
    @Override
    public Optional<ScmCredential> findPersonal(String userId) {
        if (!cipher.isEnabled()) {
            return Optional.empty();
        }
        return userGitConfigRepo.findByUserId(userId)
                .filter(UserGitConfig::hasCredential)
                .flatMap(this::decryptPersonal);
    }

    private Optional<ScmCredential> decryptPersonal(UserGitConfig config) {
        try {
            String plainToken = cipher.decrypt(config.getCredentialPasswordCipher());
            return Optional.of(new ScmCredential(config.getCredentialUsername(), plainToken, false));
        } catch (RuntimeException e) {
            log.warn("scm-credential-personal-decrypt-failed userId={} reason={}",
                    config.getUserId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 系统默认账号凭证:env {@code AGENT_GITLAB_DEFAULT_TOKEN} 优先,次之 app_setting 密文解密。
     *
     * @return 默认账号凭证;用户名未配置或两处都无 token 时 empty
     */
    @Override
    public Optional<ScmCredential> findDefaultAccount() {
        if (defaultUsername == null || defaultUsername.isBlank()) {
            return Optional.empty();
        }
        String envToken = envReader.apply(ENV_DEFAULT_TOKEN);
        if (envToken != null && !envToken.isBlank()) {
            return Optional.of(new ScmCredential(defaultUsername, envToken.trim(), true));
        }
        return findDefaultFromSetting();
    }

    private Optional<ScmCredential> findDefaultFromSetting() {
        Optional<String> stored = appSettings.get(SETTING_DEFAULT_TOKEN);
        if (stored.isEmpty() || !cipher.isEnabled()) {
            return Optional.empty();
        }
        try {
            String plainToken = cipher.decrypt(stored.get());
            return Optional.of(new ScmCredential(defaultUsername, plainToken, true));
        } catch (RuntimeException e) {
            log.warn("scm-credential-default-decrypt-failed key={} reason={}",
                    SETTING_DEFAULT_TOKEN, e.getMessage());
            return Optional.empty();
        }
    }
}
