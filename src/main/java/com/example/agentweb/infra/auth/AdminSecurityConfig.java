package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.UserContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 管理后台 ADMIN 角色鉴权的 Servlet Filter 注册。
 *
 * <p>用 {@link FilterRegistrationBean} 注册 {@link AdminAuthFilter},刻意不让它当 {@code @Component} 的 Filter Bean:
 * 后者会被 {@code @WebMvcTest} 切片自动扫描进上下文。走注册 Bean 后切片不再加载本过滤器，
 * 全栈 {@code @SpringBootTest} 与生产环境照常装配。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Configuration
public class AdminSecurityConfig {

    /** 普通用户会话过滤器顺序为 2，管理台闸门排其后。 */
    private static final int ADMIN_FILTER_ORDER = 3;

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilterRegistration(
            UserContext userContext, AdminProperties properties) {
        FilterRegistrationBean<AdminAuthFilter> registration =
                new FilterRegistrationBean<>(new AdminAuthFilter(userContext, properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(ADMIN_FILTER_ORDER);
        return registration;
    }
}
