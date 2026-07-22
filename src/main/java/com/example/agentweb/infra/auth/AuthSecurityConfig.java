package com.example.agentweb.infra.auth;

import com.example.agentweb.app.auth.AuthAppService;
import com.example.agentweb.domain.auth.ManualSessionAuthenticator;
import com.example.agentweb.domain.auth.ManualSessionFactory;
import com.example.agentweb.domain.auth.ManualSessionRepository;
import com.example.agentweb.domain.auth.PasswordHasher;
import com.example.agentweb.domain.auth.PasswordVerifier;
import com.example.agentweb.domain.auth.UserAccountRepository;
import com.example.agentweb.domain.auth.UserAuthenticator;
import com.example.agentweb.domain.auth.UserPasswordService;
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
    private static final int SECURITY_HEADERS_FILTER_ORDER = 0;
    private static final String DUMMY_PASSWORD_HASH =
            "$2b$12$DKOR1h0GGLppD.lpcl94N.TqktMUO3Bmh19O.moh9qhPzY/..ZdR.";

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilterRegistration(
            AuthProperties authProperties) {
        FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter(authProperties.isCookieSecure()));
        registration.addUrlPatterns("/*");
        registration.setOrder(SECURITY_HEADERS_FILTER_ORDER);
        return registration;
    }

    @Bean
    public ManualSessionAuthenticator manualSessionAuthenticator(ManualSessionRepository repository,
                                                                 UserAccountRepository userAccountRepository) {
        return new ManualSessionAuthenticator(repository, userAccountRepository, Clock.systemUTC());
    }

    @Bean
    public UserAuthenticator userAuthenticator(UserAccountRepository userAccountRepository,
                                               PasswordVerifier passwordVerifier) {
        return new UserAuthenticator(userAccountRepository, passwordVerifier, DUMMY_PASSWORD_HASH);
    }

    @Bean
    public UserPasswordService userPasswordService(UserAccountRepository userAccountRepository,
                                                   ManualSessionRepository sessionRepository,
                                                   PasswordHasher passwordHasher) {
        return new UserPasswordService(
                userAccountRepository, sessionRepository, passwordHasher, Clock.systemUTC());
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
