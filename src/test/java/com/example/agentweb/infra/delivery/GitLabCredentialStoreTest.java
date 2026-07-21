package com.example.agentweb.infra.delivery;

import com.example.agentweb.adapter.delivery.ScmCredential;
import com.example.agentweb.domain.git.UserGitConfig;
import com.example.agentweb.domain.git.UserGitConfigRepository;
import com.example.agentweb.infra.git.GitCredentialCipher;
import com.example.agentweb.infra.setting.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GitLab 凭证读取实现单测:个人凭证解密链路 + 默认账号 env 优先/app_setting 回落。
 * cipher 用真实实例(测试内生成 32 字节密钥),读 env 走注入的 envReader。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@ExtendWith(MockitoExtension.class)
public class GitLabCredentialStoreTest {

    private static final String USER_ID = "V33215020";
    private static final String DEFAULT_USERNAME = "agent-bot";
    private static final String ENV_TOKEN_KEY = "AGENT_GITLAB_DEFAULT_TOKEN";

    @Mock
    private UserGitConfigRepository userGitConfigRepo;

    @Mock
    private AppSettingRepository appSettings;

    private GitCredentialCipher cipher;

    @BeforeEach
    public void setUp() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new GitCredentialCipher(Base64.getEncoder().encodeToString(key));
    }

    private GitLabCredentialStore storeWithEnv(Function<String, String> envReader) {
        return new GitLabCredentialStore(userGitConfigRepo, cipher, appSettings, DEFAULT_USERNAME, envReader);
    }

    private UserGitConfig configWithCredential(String username, String passwordCipher) {
        return UserGitConfig.restore(USER_ID, null, username, passwordCipher, Instant.now());
    }

    // ---- findPersonal ----

    @Test
    public void findPersonal_should_decrypt_and_return_personal_credential() {
        // given
        when(userGitConfigRepo.findByUserId(USER_ID))
                .thenReturn(Optional.of(configWithCredential("alice", cipher.encrypt("tok-personal"))));

        // when
        Optional<ScmCredential> found = storeWithEnv(name -> null).findPersonal(USER_ID);

        // then
        assertTrue(found.isPresent());
        assertEquals("alice", found.get().getUsername());
        assertEquals("tok-personal", found.get().getToken());
        assertFalse(found.get().isDefaultAccount());
    }

    @Test
    public void findPersonal_without_config_or_credential_should_return_empty() {
        // given: 无配置行 / 有配置但未录凭证 两种未配置形态
        when(userGitConfigRepo.findByUserId("no-config")).thenReturn(Optional.empty());
        when(userGitConfigRepo.findByUserId(USER_ID))
                .thenReturn(Optional.of(UserGitConfig.restore(USER_ID, null, null, null, Instant.now())));

        GitLabCredentialStore store = storeWithEnv(name -> null);

        // then
        assertTrue(store.findPersonal("no-config").isEmpty());
        assertTrue(store.findPersonal(USER_ID).isEmpty());
    }

    @Test
    public void findPersonal_with_cipher_disabled_should_return_empty() {
        // given: 密钥缺失 → cipher 禁用
        GitLabCredentialStore store = new GitLabCredentialStore(
                userGitConfigRepo, new GitCredentialCipher((String) null), appSettings,
                DEFAULT_USERNAME, name -> null);

        // then: 不触达解密,直接 empty
        assertTrue(store.findPersonal(USER_ID).isEmpty());
    }

    @Test
    public void findPersonal_with_undecryptable_cipher_text_should_return_empty() {
        // given: 密文格式非法,decrypt 抛异常
        when(userGitConfigRepo.findByUserId(USER_ID))
                .thenReturn(Optional.of(configWithCredential("alice", "v1:not-really-cipher")));

        // then: 吞异常降级 empty,不向上抛
        assertTrue(storeWithEnv(name -> null).findPersonal(USER_ID).isEmpty());
    }

    // ---- findDefaultAccount ----

    @Test
    public void findDefaultAccount_should_prefer_env_token() {
        // given
        GitLabCredentialStore store = storeWithEnv(
                name -> ENV_TOKEN_KEY.equals(name) ? "tok-from-env" : null);

        // when
        Optional<ScmCredential> found = store.findDefaultAccount();

        // then: env 命中即返回,不再触达 app_setting
        assertTrue(found.isPresent());
        assertEquals(DEFAULT_USERNAME, found.get().getUsername());
        assertEquals("tok-from-env", found.get().getToken());
        assertTrue(found.get().isDefaultAccount());
        verifyNoInteractions(appSettings);
    }

    @Test
    public void findDefaultAccount_should_fall_back_to_encrypted_app_setting() {
        // given: env 无值,app_setting 存 cipher.encrypt 的密文
        when(appSettings.get("delivery.gitlab.default-token"))
                .thenReturn(Optional.of(cipher.encrypt("tok-from-setting")));

        // when
        Optional<ScmCredential> found = storeWithEnv(name -> null).findDefaultAccount();

        // then
        assertTrue(found.isPresent());
        assertEquals("tok-from-setting", found.get().getToken());
        assertTrue(found.get().isDefaultAccount());
    }

    @Test
    public void findDefaultAccount_without_env_and_setting_should_return_empty() {
        when(appSettings.get("delivery.gitlab.default-token")).thenReturn(Optional.empty());

        assertTrue(storeWithEnv(name -> null).findDefaultAccount().isEmpty());
    }

    @Test
    public void findDefaultAccount_with_blank_default_username_should_return_empty() {
        // given: 默认账号未配置(username 空白),即使 env 有 token 也拒绝
        GitLabCredentialStore store = new GitLabCredentialStore(
                userGitConfigRepo, cipher, appSettings, "  ",
                name -> ENV_TOKEN_KEY.equals(name) ? "tok-from-env" : null);

        assertTrue(store.findDefaultAccount().isEmpty());
    }
}
