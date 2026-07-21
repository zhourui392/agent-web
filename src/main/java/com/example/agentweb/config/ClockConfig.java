package com.example.agentweb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * 应用级共享 {@link Clock} bean。
 *
 * <p>refinery 模块开启后会注册 {@code chatRagClock}；为保证未带 {@code @Qualifier} 的 Clock
     * 注入仍能唯一匹配，这里以 {@link Primary} 提供系统 UTC 时钟作为默认。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-19
 */
@Configuration
public class ClockConfig {

    @Primary
    @Bean(name = "systemClock")
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
