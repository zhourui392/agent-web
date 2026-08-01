package com.example.agentweb.app.runtime.port;

/**
 * 采集并验证一次中性 Runtime 选择的技术可执行事实。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface RuntimePreflightGateway {

    RuntimePreflightReport inspect(RuntimePreflightRequest request);
}
