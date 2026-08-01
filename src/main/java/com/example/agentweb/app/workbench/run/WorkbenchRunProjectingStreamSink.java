package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunStreamSink;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 把公共 ChatRun Event 投影为 Workbench 前端要求的 exact envelope。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkbenchRunProjectingStreamSink
        implements ChatRunStreamSink {

    private final WorkbenchRunSnapshot snapshot;
    private final WorkbenchRunStreamSink delegate;
    private final WorkbenchTelemetry telemetry;
    private final Clock clock;

    WorkbenchRunProjectingStreamSink(
            WorkbenchRunSnapshot snapshot,
            WorkbenchRunStreamSink delegate,
            WorkbenchTelemetry telemetry,
            Clock clock) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void send(ChatRunEvent event) {
        telemetry.eventLag(Duration.between(
                event.getCreatedAt(), clock.instant()));
        String payload = WorkbenchRunEventPayloadFactory.project(
                snapshot, event);
        delegate.send(new WorkbenchRunEvent(
                event.getSeq(), event.getEventType(), payload));
    }

    @Override
    public void ping() {
        delegate.ping();
    }

    @Override
    public void complete() {
        delegate.complete();
    }

    @Override
    public void fail(Throwable error) {
        delegate.fail(error);
    }
}
