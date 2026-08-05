package com.example.agentweb.app.workbench.conversation;

/**
 * 服务端动态 Stage Session Identifier 生成端口。
 *
 * @author alex
 * @since 2026-08-05
 */
@FunctionalInterface
public interface WorkbenchStageSessionIdGenerator {

    String nextId();
}
