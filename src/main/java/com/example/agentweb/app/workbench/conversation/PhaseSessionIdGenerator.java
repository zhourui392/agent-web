package com.example.agentweb.app.workbench.conversation;

/**
 * 服务端 Phase Session ID 生成端口，客户端永不提供 sessionId。
 *
 * @author alex
 * @since 2026-08-01
 */
@FunctionalInterface
public interface PhaseSessionIdGenerator {

    String nextId();
}
