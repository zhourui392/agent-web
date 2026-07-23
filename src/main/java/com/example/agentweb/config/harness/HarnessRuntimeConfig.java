package com.example.agentweb.config.harness;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Harness Runtime 监控任务的托管线程池配置。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Configuration
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessRuntimeConfig {

    /**
     * 创建由 Spring 负责关闭的有界 Runtime 监控执行器。
     *
     * @return Runtime 监控执行器
     */
    @Bean(name = "harnessRuntimeTaskExecutor")
    public ThreadPoolTaskExecutor harnessRuntimeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(0);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(30);
        executor.setDaemon(true);
        executor.setThreadNamePrefix("harness-runtime-monitor-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
