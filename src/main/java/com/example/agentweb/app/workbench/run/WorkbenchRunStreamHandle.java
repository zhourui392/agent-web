package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunStreamHandle;

import java.util.Objects;

/**
 * 隐藏公共 ChatRun 订阅实现的 Workbench 流生命周期句柄。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchRunStreamHandle {

    private final ChatRunStreamHandle delegate;

    private WorkbenchRunStreamHandle(ChatRunStreamHandle delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static WorkbenchRunStreamHandle from(
            ChatRunStreamHandle delegate) {
        return new WorkbenchRunStreamHandle(delegate);
    }

    public void close() {
        delegate.close();
    }
}
