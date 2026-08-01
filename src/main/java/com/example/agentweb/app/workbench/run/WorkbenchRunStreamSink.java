package com.example.agentweb.app.workbench.run;

/**
 * Workbench Run SSE 的 transport-neutral 应用边界。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchRunStreamSink {

    void send(WorkbenchRunEvent event);

    void ping();

    void complete();

    void fail(Throwable error);
}
