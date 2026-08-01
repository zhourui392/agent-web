package com.example.agentweb.domain.workbench;

/**
 * 必须分别建模和授权的高影响操作；不提供 CUSTOM 逃生口。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum HighImpactOperationType {
    GIT_COMMIT,
    GIT_PUSH,
    LOCAL_DEPLOY,
    PRODUCTION_WRITE
}
