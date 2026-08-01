package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.chatrun.ChatRunId;

import java.time.Instant;
import java.util.Optional;

/**
 * ChatRun 与公共 RuntimeHandle 的稳定持久化绑定端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface ChatRunRuntimeHandleStore {

    void bind(ChatRunId runId, RuntimeHandle handle, Instant boundAt);

    Optional<RuntimeHandle> find(ChatRunId runId);

    void delete(ChatRunId runId);
}
