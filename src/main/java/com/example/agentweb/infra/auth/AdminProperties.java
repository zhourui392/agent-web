package com.example.agentweb.infra.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 管理后台(/admin)独立口令鉴权配置。前缀 {@code agent.admin}。
 *
 * <p>口令仅从环境变量注入(application.yml 写 {@code ${ADMIN_PASSWORD:}}),严禁硬编码明文。
 * 口令留空 = 管理台禁用，任何登录请求都被拒绝。该鉴权与普通用户会话正交，
 * 管理台数据接口始终由本口令独立把关。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Component
@ConfigurationProperties(prefix = "agent.admin")
@Getter
@Setter
public class AdminProperties {

    /** 是否启用管理后台独立口令鉴权;关闭后 AdminAuthFilter 直接透传。 */
    private boolean enabled = true;

    /** 管理口令明文,仅从环境变量取;留空表示管理台禁用。 */
    private String password = "";

    /** 登录会话有效期(分钟),超时后需重新登录。 */
    private long sessionTtlMinutes = 480L;

    /** 管理会话 Cookie 名，与普通用户登录 Cookie 区分。 */
    private String cookieName = "admin_session";

    /**
     * 受管理口令保护的接口前缀集合。默认仅 {@code /api/metrics};
     * 诊断历史 / 经验回填迁入 admin 时(见 ChatPanel 组件化方案 Phase 2),
     * 通过配置追加 {@code /api/diagnose-history}、{@code /api/issue-log-backfill},
     * 让 protection 与前端 UI 迁移原子上线,避免主控台 UI 尚在时被提前 401。
     */
    private List<String> protectedPrefixes =
            new ArrayList<>(Collections.singletonList("/api/metrics"));
}
