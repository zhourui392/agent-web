package com.example.agentweb.app.chatrun;

import lombok.Getter;

/**
 * Read-side message projection used only to prepare a run prompt after rewind.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ChatRunHistoryMessageView {

    private final String role;
    private final String content;

    public ChatRunHistoryMessageView(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
