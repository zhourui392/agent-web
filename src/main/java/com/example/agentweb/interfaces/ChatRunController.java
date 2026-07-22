package com.example.agentweb.interfaces;

import com.example.agentweb.app.chatrun.ActiveChatRunView;
import com.example.agentweb.app.chatrun.ChatRunAppService;
import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunStreamHandle;
import com.example.agentweb.app.chatrun.ChatRunStreamSettings;
import com.example.agentweb.app.chatrun.ChatRunStreamSink;
import com.example.agentweb.app.chatrun.ChatRunSubmission;
import com.example.agentweb.app.chatrun.ChatRunSubscriptionService;
import com.example.agentweb.app.chatrun.ChatRunView;
import com.example.agentweb.app.chatrun.EventCursorExpiredException;
import com.example.agentweb.app.chatrun.InvalidIdempotencyKeyException;
import com.example.agentweb.app.chatrun.ResumableChatStreamDisabledException;
import com.example.agentweb.app.chatrun.SubmitChatRunCommand;
import com.example.agentweb.interfaces.dto.ChatRunSubmitRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP boundary for idempotent run submission, status, stop and resumable SSE events.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@RestController
@RequestMapping(path = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatRunController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatRunAppService appService;
    private final ChatRunSubscriptionService subscriptionService;
    private final ChatRunStreamSettings settings;

    public ChatRunController(ChatRunAppService appService,
                             ChatRunSubscriptionService subscriptionService,
                             ChatRunStreamSettings settings) {
        this.appService = appService;
        this.subscriptionService = subscriptionService;
        this.settings = settings;
    }

    @PostMapping("/session/{sessionId}/runs")
    public ResponseEntity<ChatRunSubmission> submit(
            @PathVariable("sessionId") String sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ChatRunSubmitRequest request) {
        requireEnabled();
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()
                || idempotencyKey.trim().length() > 128) {
            throw new InvalidIdempotencyKeyException();
        }
        ChatRunSubmission result = appService.submit(new SubmitChatRunCommand(
                sessionId, request.getMessage(), request.getResumeId(),
                request.isRecallEnabled(), idempotencyKey));
        return ResponseEntity.accepted()
                .location(URI.create("/api/chat/runs/" + result.getRunId()))
                .body(result);
    }

    @GetMapping("/runs/active")
    public List<ActiveChatRunView> active() {
        requireEnabled();
        return appService.findActive();
    }

    @GetMapping("/runs/{runId}")
    public ChatRunView find(@PathVariable("runId") String runId) {
        requireEnabled();
        return appService.find(runId);
    }

    @PostMapping("/runs/{runId}/stop")
    public ResponseEntity<ChatRunView> stop(@PathVariable("runId") String runId) {
        requireEnabled();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(appService.stop(runId));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "after", required = false) Long after,
            HttpServletResponse response) {
        requireEnabled();
        final long cursor = resolveCursor(lastEventId, after);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        final SseEmitter emitter = new SseEmitter(-1L);
        final AtomicReference<ChatRunStreamHandle> handleReference =
                new AtomicReference<ChatRunStreamHandle>();
        ChatRunStreamHandle handle;
        try {
            handle = subscriptionService.subscribe(runId, cursor, new EmitterSink(emitter));
        } catch (EventCursorExpiredException ex) {
            writeExpiredCursor(response, ex);
            return null;
        }
        handleReference.set(handle);
        emitter.onCompletion(() -> close(handleReference));
        emitter.onTimeout(() -> close(handleReference));
        emitter.onError(error -> close(handleReference));

        return emitter;
    }

    private void writeExpiredCursor(HttpServletResponse response, EventCursorExpiredException ex) {
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

    private void requireEnabled() {
        if (!settings.isEnabled()) {
            throw new ResumableChatStreamDisabledException();
        }
    }

    private void close(AtomicReference<ChatRunStreamHandle> reference) {
        ChatRunStreamHandle handle = reference.get();
        if (handle != null) {
            handle.close();
        }
    }

    private static final class EmitterSink implements ChatRunStreamSink {

        private final SseEmitter emitter;

        private EmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(ChatRunEvent event) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.getSeq()))
                        .name(event.getEventType())
                        .data(event.getPayload()));
            } catch (IOException ex) {
                throw new IllegalStateException("could not send run event", ex);
            }
        }

        @Override
        public void ping() {
            try {
                emitter.send(SseEmitter.event().name("ping").data(""));
            } catch (IOException ex) {
                throw new IllegalStateException("could not send run heartbeat", ex);
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
