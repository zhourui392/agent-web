package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;

/**
 * Application port for launching one already-persisted run in the background.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunLauncher {

    void launch(ChatRunId runId);
}
