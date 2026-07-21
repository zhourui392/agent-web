package com.example.agentweb.infra.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部 API 鉴权配置。外部系统建需求入口（{@code /api/requirements/external}）复用同一把
 * API Key 体系与幂等键命名空间，通过 {@code agent.api-keys} 绑定。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-09
 */
@Component
@ConfigurationProperties(prefix = "agent")
@Getter
@Setter
public class ApiKeyProperties {

    private List<ApiKeyEntry> apiKeys = new ArrayList<>();

    /**
     * 按 key 字面值查找配置项；找不到返回 null（认证 Filter 据此判 401/403）。
     *
     * @param key 请求头携带的 API Key
     * @return 命中的配置项，或 null
     */
    public ApiKeyEntry findByKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (ApiKeyEntry entry : apiKeys) {
            if (key.equals(entry.getKey())) {
                return entry;
            }
        }
        return null;
    }

    @Getter
    @Setter
    public static class ApiKeyEntry {
        private String key;
        private String name;
        private int rateLimit = 10;
    }
}
