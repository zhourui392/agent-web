package com.example.agentweb.infra.auth;

import com.example.agentweb.app.auth.AuthAppService;
import com.example.agentweb.domain.auth.ManualSessionAuthenticator;
import com.example.agentweb.domain.auth.ManualSessionFactory;
import com.example.agentweb.domain.auth.ManualSessionRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 本地会话认证组件装配。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
@Configuration
public class AuthSecurityConfig {

    private static final int SESSION_FILTER_ORDER = 2;

    @Bean
    public ManualSessionAuthenticator manualSessionAuthenticator(ManualSessionRepository repository) {
        return new ManualSessionAuthenticator(repository, Clock.systemUTC());
    }

    @Bean
    public ManualSessionFactory manualSessionFactory(AuthProperties properties) {
        return new ManualSessionFactory(properties.getSessionTtlSeconds(), Clock.systemUTC());
    }

    @Bean
    public FilterRegistrationBean<SessionAuthFilter> sessionAuthFilterRegistration(
            AuthProperties authProperties,
            AuthAppService authAppService,
            ThreadLocalUserContext userContext) {
        SessionAuthFilter filter = new SessionAuthFilter(authProperties, authAppService, userContext);
        FilterRegistrationBean<SessionAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(SESSION_FILTER_ORDER);
        return registration;
    }
}
