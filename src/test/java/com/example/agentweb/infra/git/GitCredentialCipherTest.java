package com.example.agentweb.infra.git;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitCredentialCipher} AES-256-GCM 加解密往返 + fail-closed 单测（显式注入密钥, 不依赖进程环境）。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
class GitCredentialCipherTest {

    /** 测试用 32 字节 base64 密钥（仅测试，绝非生产密钥）。 */
    private static final String KEY_32 =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    /** 密文版本前缀，与 {@link GitCredentialCipher} 内部约定保持一致。 */
    private static final String VERSION_PREFIX = "v1:";

    @Test
    void enabled_when_valid_32_byte_key() {
        assertTrue(new GitCredentialCipher(KEY_32).isEnabled());
    }

    @Test
    void disabled_when_key_missing_or_wrong_length() {
        assertFalse(new GitCredentialCipher((String) null).isEnabled());
        assertFalse(new GitCredentialCipher("").isEnabled());
        assertFalse(new GitCredentialCipher(Base64.getEncoder().encodeToString("short".getBytes())).isEnabled());
        assertFalse(new GitCredentialCipher("not-base64!!!").isEnabled());
    }

    @Test
    void encrypt_then_decrypt_should_round_trip() {
        GitCredentialCipher cipher = new GitCredentialCipher(KEY_32);
        String plain = "ghp_secretToken_中文密码";

        String encrypted = cipher.encrypt(plain);

        assertTrue(encrypted.startsWith("v1:"));
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    void ciphertext_should_not_contain_plaintext() {
        GitCredentialCipher cipher = new GitCredentialCipher(KEY_32);
        String plain = "PLAINTEXT_MARKER";
        String encrypted = cipher.encrypt(plain);
        assertFalse(encrypted.contains(plain), "密文不得含明文");
    }

    @Test
    void two_encryptions_should_differ_due_to_random_iv() {
        GitCredentialCipher cipher = new GitCredentialCipher(KEY_32);
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"));
    }

    @Test
    void decrypt_tampered_payload_should_fail() {
        GitCredentialCipher cipher = new GitCredentialCipher(KEY_32);
        String encrypted = cipher.encrypt("data");
        // 在解码后的字节上翻转 GCM tag 末字节的一位, 确定性篡改密文 (避免直接改 base64 末字符,
        // 那会因 padding bit 对齐而在 1/16 概率下是空操作, 导致本测试 flaky)。
        String body = encrypted.substring(VERSION_PREFIX.length());
        byte[] raw = Base64.getDecoder().decode(body);
        raw[raw.length - 1] ^= 0x01;
        String tampered = VERSION_PREFIX + Base64.getEncoder().encodeToString(raw);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void encrypt_or_decrypt_when_disabled_should_throw() {
        GitCredentialCipher disabled = new GitCredentialCipher((String) null);
        assertThrows(IllegalStateException.class, () -> disabled.encrypt("x"));
        assertThrows(IllegalStateException.class, () -> disabled.decrypt("v1:abc"));
    }

    @Test
    void decrypt_bad_format_should_throw_illegal_argument() {
        GitCredentialCipher cipher = new GitCredentialCipher(KEY_32);
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt("no-version-prefix"));
    }
}
