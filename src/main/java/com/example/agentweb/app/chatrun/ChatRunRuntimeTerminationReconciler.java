package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeTermination;
import com.example.agentweb.domain.chatrun.ChatRunId;

/**
 * 使用已持久化 Run 事实收口重启期间观察到的 Runtime 终态。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface ChatRunRuntimeTerminationReconciler {

    void reconcile(
            ChatRunId runId, RuntimeHandle handle,
            RuntimeTermination termination);
}
