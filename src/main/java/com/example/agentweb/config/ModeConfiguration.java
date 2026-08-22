package com.example.agentweb.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 模式与交接的装配配置。
 *
 * @author alex
 * @since 2026-08-20
 */
@Configuration
@EnableConfigurationProperties(ModeSwitchProperties.class)
public class ModeConfiguration {
}
