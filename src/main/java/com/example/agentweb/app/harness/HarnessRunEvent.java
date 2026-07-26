package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessEvent;
import com.example.agentweb.domain.harness.HarnessStage;
import lombok.Getter;

import java.time.Instant;

/**
 * 传输层中立的 harness run 事件，由 {@link HarnessRunEventPublisher} 从聚合根缓冲转换而来，
 * 经 {@link HarnessRunEventHub} fan-out 给 SSE 订阅者。
 *
 * <p>与 chat 域的 {@code ChatRunEvent} 对应，但 harness 事件已在聚合根内分配 sequence
 * 并由 Repository 持久化，此对象只用于传输。</p>
 *
 * @author zhourui(V33215020)
 */
@Getter
public final class HarnessRunEvent {

    private final String runId;
    private final long sequence;
    private final String eventType;
    private final String stage;
    private final String actor;
    private final String detail;
    private final Instant occurredAt;

    public HarnessRunEvent(String runId, long sequence, String eventType, String stage,
                           String actor, String detail, Instant occurredAt) {
        this.runId = runId;
        this.sequence = sequence;
        this.eventType = eventType;
        this.stage = stage;
        this.actor = actor;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public static HarnessRunEvent from(String runId, HarnessEvent event) {
        HarnessStage stage = event.getStage();
        return new HarnessRunEvent(runId, event.getSequence(), event.getEventType(),
                stage == null ? null : stage.name(), event.getActor(), event.getDetail(),
                event.getOccurredAt());
    }

    public int getPayloadSize() {
        int size = 0;
        if (eventType != null) size += eventType.length();
        if (stage != null) size += stage.length();
        if (actor != null) size += actor.length();
        if (detail != null) size += detail.length();
        return size;
    }
}