package com.example.agentweb.app.requirement;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * @author zhourui(V33215020)
 */
class RunEventBusTest {

    private final RunEventBus bus = new RunEventBus();

    @Test
    void publish_should_send_to_subscriber() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        bus.subscribe("s1", emitter);

        bus.publish("s1", 1, "chunk", "hello");

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void publish_without_subscriber_should_be_noop() {
        // 无订阅者时直接返回，不抛异常
        bus.publish("missing", 1, "chunk", "hello");
    }

    @Test
    void send_failure_should_unsubscribe_emitter() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("boom")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        bus.subscribe("s3", emitter);

        bus.publish("s3", 1, "chunk", "x");
        bus.publish("s3", 2, "chunk", "y");

        // 首次失败后 emitter 被移除，第二次 publish 不再触达
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void close_should_complete_subscribers() {
        SseEmitter emitter = mock(SseEmitter.class);
        bus.subscribe("s4", emitter);

        bus.close("s4");

        verify(emitter).complete();
    }
}
