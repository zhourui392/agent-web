package com.example.agentweb.app.git;

/**
 * 用户 SCM 凭证加解密端口。app 层只面向此端口, AES-GCM 细节与密钥注入由 infra 实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface CredentialCipher {

    /** 密钥配置名（同时兼容同名环境变量）。绝不硬编码密钥本身。 */
    String ENV_KEY = "GIT_CRED_ENC_KEY";

    /**
     * 凭证功能是否可用（密钥已配置且长度合法）。未启用时调用方应降级为仅注入 git 身份。
     */
    boolean isEnabled();

    /**
     * 加密明文凭证。
     *
     * @return 带版本前缀的密文（可直接落库）
     * @throws IllegalStateException 未启用时
     */
    String encrypt(String plaintext);

    /**
     * 解密密文。
     *
     * @param stored {@link #encrypt} 产出的带版本前缀密文
     * @throws IllegalStateException 未启用或密文损坏 / 版本不支持时
     */
    String decrypt(String stored);
}
