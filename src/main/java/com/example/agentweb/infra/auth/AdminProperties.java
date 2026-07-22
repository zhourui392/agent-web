package com.example.agentweb.infra.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 管理后台 ADMIN 角色鉴权的受保护路径配置。前缀 {@code agent.admin}。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Component
@ConfigurationProperties(prefix = "agent.admin")
@Getter
@Setter
public class AdminProperties {

    /** 需要 ADMIN 数据库角色的接口前缀集合；代码默认值必须 fail-closed 覆盖全部管理能力。 */
    private List<String> protectedPrefixes = new ArrayList<>(Arrays.asList(
            "/api/metrics",
            "/api/admin-user-suggestions",
            "/api/admin-users",
            "/api/admin-workflows",
            "/api/admin-workflow-executions",
            "/api/admin-settings",
            "/api/refinery",
            "/api/issue-log-backfill",
            "/api/diagnose-history"
    ));
}
