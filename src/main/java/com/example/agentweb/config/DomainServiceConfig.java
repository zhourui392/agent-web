package com.example.agentweb.config;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.git.GitConfigPolicy;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.RepositoryChatRunActivityGuard;
import com.example.agentweb.domain.refinery.DefaultTrustTierPolicy;
import com.example.agentweb.domain.refinery.TrustTierPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 领域服务统一装配。domain 包保持零 Spring 依赖（persistence/framework-ignorant），
 * 属性读取、Bean 注册等基础设施关注点全部收敛到本配置类。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public CurrentUserProvider currentUserProvider(UserContext userContext,
            @Value("${agent.chat.user-isolation-enabled:true}") boolean isolationEnabled) {
        return new CurrentUserProvider(userContext, isolationEnabled);
    }

    @Bean
    public GitConfigPolicy gitConfigPolicy() {
        return new GitConfigPolicy();
    }

    @Bean
    public TrustTierPolicy trustTierPolicy() {
        return new DefaultTrustTierPolicy();
    }

    @Bean
    public ChatRunActivityGuard chatRunActivityGuard(ChatRunRepository runRepository) {
        return new RepositoryChatRunActivityGuard(runRepository);
    }
}
