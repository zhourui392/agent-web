package com.example.agentweb.app.harness.port;

import lombok.Getter;

/**
 * Runtime 启动失败及其临时配置清理事实。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public class AgentRuntimeStartException extends IllegalStateException {

    private final boolean temporaryConfigCleaned;

    public AgentRuntimeStartException(String message, boolean temporaryConfigCleaned,
                                      Throwable cause) {
        super(message, cause);
        this.temporaryConfigCleaned = temporaryConfigCleaned;
    }
}
