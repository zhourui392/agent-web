package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.domain.chatrun.ChatRunId;

/**
 * 从持久化事件恢复一次公共 Runtime 的规范输出。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface ChatRunRuntimeOutputQuery {

    RecoveredRuntimeOutput load(ChatRunId runId, RuntimeHandle handle);
}
