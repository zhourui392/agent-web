package com.example.agentweb.domain.runtime;

/**
 * 公共 Runtime 必须拒绝直接执行的类型化高影响操作。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum RuntimeHighImpactOperation {
    GIT_COMMIT,
    GIT_PUSH,
    LOCAL_DEPLOY,
    PRODUCTION_WRITE
}
