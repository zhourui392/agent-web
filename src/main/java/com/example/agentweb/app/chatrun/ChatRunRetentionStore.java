package com.example.agentweb.app.chatrun;

import java.time.Instant;

/**
 * Application port for deleting expired terminal ChatRun metadata.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunRetentionStore {

    int deleteTerminalRunsBefore(Instant cutoff, int limit);
}
