package com.example.agentweb.app.auth.port;

import java.util.Optional;

/**
 * 用户目录端口：外部接入 owner 的存在性软闸 + 工号 → IM 侧用户标识解析（通知 p2p 直达）。
 * 实现必须 fail-open/fail-safe：目录不可用时 {@code containsUser} 恒真、
 * {@code imUserIdOf} 返回 empty，均不得抛异常——目录是旁路能力，不得卡死接入与通知。
 *
 * @author alex
 * @since 2026-07-04
 */
public interface UserDirectory {

    /**
     * 工号是否存在于用户目录。
     *
     * @param userId 工号
     * @return 存在或目录不可用（fail-open）时 true
     */
    boolean containsUser(String userId);

    /**
     * 工号解析为 IM 平台侧用户标识（飞书 open_id）。
     *
     * @param userId 工号
     * @return 平台侧用户标识；未命中或目录不可用返回 empty
     */
    Optional<String> imUserIdOf(String userId);
}
