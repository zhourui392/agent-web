package com.example.agentweb.infra.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地登录配置，绑定 {@code agent.auth}。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
@Component
@ConfigurationProperties(prefix = "agent.auth")
@Getter
@Setter
public class AuthProperties {

    /** 本地登录会话 Cookie 名。 */
    private String cookieName = "local_session";

    /** 是否无条件为登录会话 Cookie 添加 Secure 属性。 */
    private boolean cookieSecure;

    /** 本地登录会话有效期，单位秒。 */
    private long sessionTtlSeconds = 604800L;

    /** 对外规范登录页 URL，留空时使用当前应用下的 {@code /login.html}。 */
    private String loginPageUrl = "";

    /** 同一来源 IP 或同一用户名在窗口内允许的最大失败次数。 */
    private int loginMaxFailures = 5;

    /** 登录失败计数窗口，单位秒。 */
    private long loginFailureWindowSeconds = 300L;
}
