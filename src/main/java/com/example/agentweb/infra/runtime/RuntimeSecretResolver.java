package com.example.agentweb.infra.runtime;

/**
 * Runtime 启动边界的 Secret Reference 解析器。
 *
 * <p>返回值只能保留在单次 Runtime 物化对象中，禁止进入 Snapshot、Prompt、日志或 API。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public interface RuntimeSecretResolver {

    char[] resolve(String reference);
}
