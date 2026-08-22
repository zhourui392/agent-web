package com.example.agentweb.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模式切换配置。
 *
 * @author alex
 * @since 2026-08-20
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent.mode-switch")
public class ModeSwitchProperties {

    /** 交接转录保留的尾部消息条数。 */
    private int transcriptTail = 40;
}
