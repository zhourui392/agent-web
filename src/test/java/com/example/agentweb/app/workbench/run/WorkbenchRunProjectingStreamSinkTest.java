package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 公共 Runtime/terminal/stop 事件到 Dynamic Stage SSE envelope 的投影测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchRunProjectingStreamSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {
            };

    @Test
    void should_EnvelopeEventsWithStageIdentity_When_ProjectingSse()
            throws Exception {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        WorkbenchStageRunSnapshot snapshot = fixture.snapshot();
        RecordingSink delegate = new RecordingSink();
        WorkbenchTelemetry telemetry = mock(WorkbenchTelemetry.class);
        WorkbenchRunProjectingStreamSink sink =
                new WorkbenchRunProjectingStreamSink(
                        snapshot, delegate, telemetry,
                        Clock.fixed(
                                WorkbenchStageRunTestFixtures.NOW
                                        .plusMillis(750L),
                                ZoneOffset.UTC));
        Instant occurredAt = WorkbenchStageRunTestFixtures.NOW;
        String stopPayload = WorkbenchRunEventPayloadFactory.status(
                snapshot, ChatRunStatus.CANCEL_REQUESTED, occurredAt);

        // When
        sink.send(event(1L, "runtime_started",
                "{\"runtimeSequence\":1,\"runtimeType\":\"STARTED\",\"payload\":\"ready\"}",
                occurredAt));
        sink.send(event(2L, "runtime_output",
                "{\"runtimeSequence\":2,\"runtimeType\":\"OUTPUT\",\"payload\":\"hello\"}",
                occurredAt));
        sink.send(event(3L, "runtime_diagnostic",
                "{\"runtimeSequence\":3,\"runtimeType\":\"DIAGNOSTIC\",\"payload\":\"safe\"}",
                occurredAt));
        sink.send(event(4L, "terminal",
                "{\"status\":\"CANCELLED\",\"failureCode\":null,\"publicMessage\":null}",
                occurredAt));
        sink.send(event(5L, "run_status", stopPayload, occurredAt));

        // Then
        assertEquals(5, delegate.events.size());
        for (WorkbenchRunEvent projected : delegate.events) {
            Map<String, Object> envelope = MAPPER.readValue(
                    projected.getPayload(), MAP_TYPE);
            assertEquals("workbench-run-event@1",
                    envelope.get("schemaVersion"));
            assertEquals(
                    WorkbenchStageRunTestFixtures.WORKBENCH_ID.getValue(),
                    envelope.get("workbenchId"));
            assertEquals(
                    WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER,
                    envelope.get("stageInstanceIdentifier"));
            assertEquals(WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                    envelope.get("runId"));
            assertFalse(envelope.containsKey("phase"));
            assertTrue(envelope.get("data") instanceof Map);
        }
        assertEquals("hello", data(delegate.events.get(1)).get("payload"));
        assertEquals("safe", data(delegate.events.get(2)).get("payload"));
        assertEquals("CANCELLED", data(delegate.events.get(3)).get("status"));
        assertEquals("CANCEL_REQUESTED",
                data(delegate.events.get(4)).get("status"));
        assertEquals(stopPayload, delegate.events.get(4).getPayload());
        assertEquals(Arrays.asList(
                        "runtime_started", "runtime_output",
                        "runtime_diagnostic", "terminal", "run_status"),
                eventTypes(delegate.events));
        verify(telemetry, times(5)).eventLag(
                Duration.ofMillis(750L));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(WorkbenchRunEvent event) throws Exception {
        return (Map<String, Object>) MAPPER.readValue(
                event.getPayload(), MAP_TYPE).get("data");
    }

    private ChatRunEvent event(
            long sequence, String type, String payload,
            Instant occurredAt) {
        return new ChatRunEvent(
                ChatRunId.of(WorkbenchStageRunTestFixtures.RUN_IDENTIFIER),
                sequence, type, payload,
                payload.length(), occurredAt);
    }

    private List<String> eventTypes(List<WorkbenchRunEvent> events) {
        List<String> types = new ArrayList<String>();
        for (WorkbenchRunEvent event : events) {
            types.add(event.getEventType());
        }
        return types;
    }

    private static final class RecordingSink
            implements WorkbenchRunStreamSink {

        private final List<WorkbenchRunEvent> events =
                new ArrayList<WorkbenchRunEvent>();

        @Override
        public void send(WorkbenchRunEvent event) {
            events.add(event);
        }

        @Override
        public void ping() {
        }

        @Override
        public void complete() {
        }

        @Override
        public void fail(Throwable error) {
        }
    }
}
