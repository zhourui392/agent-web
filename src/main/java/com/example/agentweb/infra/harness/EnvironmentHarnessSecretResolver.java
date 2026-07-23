package com.example.agentweb.infra.harness;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MVP 从服务进程环境按逻辑键解析 Secret，不读取用户 CLI 认证目录。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class EnvironmentHarnessSecretResolver implements HarnessSecretResolver {

    @Override
    public String resolve(String reference) {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IllegalArgumentException("secret reference must not be blank");
        }
        String value = System.getenv(reference.trim());
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("required secret reference is unavailable");
        }
        return value;
    }
}
