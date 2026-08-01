package com.example.agentweb.app.workbench.run;

import lombok.Getter;

/**
 * Interface 可消费的 Workbench Run 安全事件投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunEvent {

    private final long sequence;
    private final String eventType;
    private final String payload;

    WorkbenchRunEvent(long sequence, String eventType, String payload) {
        this.sequence = sequence;
        this.eventType = eventType;
        this.payload = payload;
    }
}
