package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.HarnessEventCursorExpiredException;
import com.example.agentweb.app.harness.HarnessRunEvent;
import com.example.agentweb.app.harness.HarnessRunStreamHandle;
import com.example.agentweb.app.harness.HarnessRunStreamSink;
import com.example.agentweb.app.harness.HarnessSubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * harness run SSE 流端点，复用 chat 域 {@code ChatRunController} 的 EmitterSink 模式。
 *
 * <p>路径 {@code /api/harness/runs/{runId}/stream} 与 JSON 端点
 * {@code /api/harness/runs/{runId}/events} 不冲突。AdminAuthFilter 已保护 {@code /api/harness} 前缀。</p>
 *
 * @author zhourui(V33215020)
 */
@RestController
@RequestMapping(path = "/api/harness", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessStreamController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HarnessSubscriptionService subscriptionService;

    public HarnessStreamController(HarnessSubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "after", required = false) Long after,
            HttpServletResponse response) {
        final long cursor = resolveCursor(lastEventId, after);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        final SseEmitter emitter = new SseEmitter(-1L);
        final AtomicReference<HarnessRunStreamHandle> handleReference =
                new AtomicReference<HarnessRunStreamHandle>();
        HarnessRunStreamHandle handle;
        try {
            handle = subscriptionService.subscribe(runId, cursor, new EmitterSink(emitter));
        } catch (HarnessEventCursorExpiredException ex) {
            writeExpiredCursor(response, ex);
            return null;
        }
        handleReference.set(handle);
        emitter.onCompletion(() -> close(handleReference));
        emitter.onTimeout(() -> close(handleReference));
        emitter.onError(error -> close(handleReference));
        return emitter;
    }

    private void writeExpiredCursor(HttpServletResponse response, HarnessEventCursorExpiredException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "EVENT_CURSOR_EXPIRED");
        body.put("runId", ex.getRunId());
        body.put("earliestRetainedSeq", ex.getEarliestRetainedSeq());
        body.put("lastEventSeq", ex.getLastEventSeq());
        body.put("message", ex.getMessage());
        response.setStatus(HttpStatus.GONE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            MAPPER.writeValue(response.getWriter(), body);
        } catch (IOException ioException) {
            throw new IllegalStateException("could not write expired cursor response", ioException);
        }
    }

    private long resolveCursor(String lastEventId, Long after) {
        if (lastEventId != null && !lastEventId.trim().isEmpty()) {
            try {
                return Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid Last-Event-ID");
            }
        }
        return after == null ? 0L : after.longValue();
    }

    private void close(AtomicReference<HarnessRunStreamHandle> reference) {
        HarnessRunStreamHandle handle = reference.get();
        if (handle != null) {
            handle.close();
        }
    }

    private static final class EmitterSink implements HarnessRunStreamSink {

        private final SseEmitter emitter;

        private EmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(HarnessRunEvent event) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.getSequence()))
                        .name(event.getEventType())
                        .data(event.getDetail() == null ? "" : event.getDetail()));
            } catch (IOException ex) {
                throw new IllegalStateException("could not send harness run event", ex);
            }
        }

        @Override
        public void ping() {
            try {
                emitter.send(SseEmitter.event().name("ping").data(""));
            } catch (IOException ex) {
                throw new IllegalStateException("could not send harness heartbeat", ex);
            }
        }

        @Override
        public void complete() {
            emitter.complete();
        }

        @Override
        public void fail(Throwable error) {
            emitter.completeWithError(error);
        }
    }
}