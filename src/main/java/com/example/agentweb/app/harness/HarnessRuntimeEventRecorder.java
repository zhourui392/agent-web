package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.RuntimeEventSink;

/**
 * Runtime 事件和启动失败的应用事务入口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface HarnessRuntimeEventRecorder extends RuntimeEventSink {

    /**
     * 记录进程尚未建立时的启动失败及清理结果。
     *
     * @param executionId Execution ID
     * @param reason 非敏感失败原因
     * @param temporaryConfigCleaned 临时配置是否清理
     */
    void recordStartFailure(String executionId, String reason, boolean temporaryConfigCleaned);
}
