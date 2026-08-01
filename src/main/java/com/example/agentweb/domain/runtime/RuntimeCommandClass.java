package com.example.agentweb.domain.runtime;

/**
 * 可安全展示的 Runtime 命令类别，不包含原始命令文本。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum RuntimeCommandClass {
    SHELL,
    GIT,
    TEST,
    BUILD,
    DEPLOY,
    PRODUCTION_WRITE
}
