package com.example.agentweb.app.harness;

/**
 * Harness 技术标识生成端口，便于应用编排测试保持确定性。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface HarnessIdGenerator {

    String nextId();
}
