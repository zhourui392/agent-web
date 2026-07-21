package com.example.agentweb.domain.chat;

import java.util.UUID;

/**
 * 会话分享 token 生成规则：16 位小写十六进制（UUID 前缀），token 即公开链接的全部授权。
 * 格式是领域约定，禁止调用方自行拼装。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
public final class ShareToken {

    private static final int LENGTH = 16;

    private ShareToken() {
    }

    /**
     * 生成一个新的分享 token。
     *
     * @return 16 位小写十六进制字符串
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, LENGTH);
    }
}
