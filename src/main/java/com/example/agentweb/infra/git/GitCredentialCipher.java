package com.example.agentweb.infra.git;

import com.example.agentweb.app.git.CredentialCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * push 凭证密码的 AES-256-GCM 加解密。密钥通过 Spring 配置项 {@code GIT_CRED_ENC_KEY}
 * （32 字节 base64）注入，可来自环境变量或 Git 忽略的 {@code data/secrets.properties}；
 * 绝不进入受 Git 跟踪的 yml，也不回退默认密钥。缺失或长度不对 → 降级为「禁用」（仅身份可用），启动 WARN。
 *
 * <p>密文格式 {@code v1:base64(iv(12) ‖ ciphertext ‖ tag(16))}，每条随机 12 字节 IV，
 * {@code v1:} 前缀留密钥轮转余地。封装密钥来源，将来换 KMS 仅改此类。</p>
 *
 * <p>威胁模型：防 {@code .db} 单独泄漏 / 备份，不防主机沦陷（同机 root 可同时拿到密钥与库）。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Component
@Slf4j
public class GitCredentialCipher implements CredentialCipher {

    /** 密钥配置名（同时兼容同名环境变量）。绝不硬编码密钥本身。 */
    public static final String ENV_KEY = "GIT_CRED_ENC_KEY";

    private static final String VERSION_PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 32 字节密钥；缺失 / 长度非法时为 null（禁用）。 */
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    /**
     * 配置/显式密钥构造。生产由 Spring 从环境变量或 {@code data/secrets.properties} 注入，
     * 测试直接传入已知密钥，不依赖进程环境。
     *
     * @param base64Key 32 字节密钥的 base64；{@code null}/空/长度非法 → 禁用
     */
    public GitCredentialCipher(@Value("${GIT_CRED_ENC_KEY:}") String base64Key) {
        this.key = decodeKey(base64Key);
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Key.trim());
            if (decoded.length != KEY_BYTES) {
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException badBase64) {
            return null;
        }
    }

    @PostConstruct
    void logStatus() {
        if (key == null) {
            log.warn("git-cred-cipher-disabled config={} 缺失或非 32 字节 base64; push 凭证不可用, 仅 git 提交身份生效",
                    ENV_KEY);
        } else {
            log.info("git-cred-cipher-enabled keyBits={}", KEY_BYTES * 8);
        }
    }

    /**
     * 是否已启用（密钥就绪）。凭证相关调用方须先判此再 encrypt/decrypt。
     *
     * @return 密钥就绪时 true
     */
    public boolean isEnabled() {
        return key != null;
    }

    /**
     * 加密明文密码。
     *
     * @param plaintext 明文
     * @return {@code v1:base64(iv‖ciphertext‖tag)}
     * @throws IllegalStateException 未启用
     */
    public String encrypt(String plaintext) {
        if (key == null) {
            throw new IllegalStateException("git credential cipher disabled: " + ENV_KEY + " not configured");
        }
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherTextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherTextWithTag.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherTextWithTag, 0, combined, iv.length, cipherTextWithTag.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("git credential encrypt failed", e);
        }
    }

    /**
     * 解密密文。
     *
     * @param stored {@code v1:...} 密文
     * @return 明文密码
     * @throws IllegalStateException    未启用
     * @throws IllegalArgumentException 格式非法
     */
    public String decrypt(String stored) {
        if (key == null) {
            throw new IllegalStateException("git credential cipher disabled: " + ENV_KEY + " not configured");
        }
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException("unsupported git credential cipher format");
        }
        byte[] combined = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
        if (combined.length <= IV_BYTES) {
            throw new IllegalArgumentException("git credential cipher payload too short");
        }
        try {
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] cipherTextWithTag = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherTextWithTag), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("git credential decrypt failed", e);
        }
    }
}
