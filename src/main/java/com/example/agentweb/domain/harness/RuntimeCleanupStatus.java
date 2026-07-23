package com.example.agentweb.domain.harness;

/**
 * Runtime 临时目录的清理结果。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum RuntimeCleanupStatus {
    /** 尚未创建临时配置。 */
    NOT_STARTED,
    /** 已创建临时配置，等待清理。 */
    PENDING,
    /** 临时配置已清理。 */
    SUCCEEDED,
    /** 清理失败，需要运维介入。 */
    FAILED
}
