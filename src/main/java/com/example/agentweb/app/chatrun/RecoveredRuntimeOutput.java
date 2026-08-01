package com.example.agentweb.app.chatrun;

import lombok.Getter;

import java.util.Objects;

/**
 * 重启恢复读取到的有界 Runtime 输出。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RecoveredRuntimeOutput {

    private final String content;
    private final boolean complete;

    private RecoveredRuntimeOutput(String content, boolean complete) {
        this.content = Objects.requireNonNull(content, "content");
        this.complete = complete;
    }

    public static RecoveredRuntimeOutput complete(String content) {
        return new RecoveredRuntimeOutput(content, true);
    }

    public static RecoveredRuntimeOutput incomplete() {
        return new RecoveredRuntimeOutput("", false);
    }
}
