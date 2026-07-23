package com.example.agentweb.infra.harness;

/**
 * Infrastructure 内部的 Secret Reference 解析器；返回值不得越过 Runtime Adapter。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface HarnessSecretResolver {

    /**
     * 只在进程启动期解析逻辑引用。
     *
     * @param reference Secret 逻辑引用
     * @return Secret 明文
     */
    String resolve(String reference);
}
