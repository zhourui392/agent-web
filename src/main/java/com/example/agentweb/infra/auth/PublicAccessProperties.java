package com.example.agentweb.infra.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 公网访问启动配置，绑定 {@code agent.public-access}。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
@ConfigurationProperties(prefix = "agent.public-access")
@Getter
@Setter
public class PublicAccessProperties {

    /** 是否按公网安全约束启动。 */
    private boolean enabled;

    /** 首次公网启动时替换已知种子密码的管理员明文密码，仅在启动阶段使用。 */
    private String bootstrapAdminPassword = "";
}
