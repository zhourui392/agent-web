package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.run.SubmitWorkbenchStageRunCommand;
import com.example.agentweb.app.workbench.run.WorkbenchRunAppService;
import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference;
import com.example.agentweb.app.workbench.run.WorkbenchRunCursorExpiredException;
import com.example.agentweb.app.workbench.run.WorkbenchRunEvent;
import com.example.agentweb.app.workbench.run.WorkbenchRunEventPage;
import com.example.agentweb.app.workbench.run.WorkbenchRunEventPageRequest;
import com.example.agentweb.app.workbench.run.WorkbenchRunCapabilityView;
import com.example.agentweb.app.workbench.run.WorkbenchRunDetailView;
import com.example.agentweb.app.workbench.run.WorkbenchRunHistoryAppService;
import com.example.agentweb.app.workbench.run.WorkbenchRunListCursor;
import com.example.agentweb.app.workbench.run.WorkbenchRunListPage;
import com.example.agentweb.app.workbench.run.WorkbenchRunListRequest;
import com.example.agentweb.app.workbench.run.WorkbenchRunStopResult;
import com.example.agentweb.app.workbench.run.WorkbenchRunStreamHandle;
import com.example.agentweb.app.workbench.run.WorkbenchRunStreamSink;
import com.example.agentweb.app.workbench.run.WorkbenchStageRunAppService;
import com.example.agentweb.app.workbench.run.WorkbenchStageRunSubmissionResult;
import com.example.agentweb.app.workbench.stage.WorkbenchStageCommandQueryService;
import com.example.agentweb.app.workbench.stage.WorkbenchStageCommandView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.interfaces.dto.CommandDto;
import com.example.agentweb.interfaces.workbench.dto.SubmitWorkbenchRunRequest;
import com.example.agentweb.interfaces.workbench.dto.WorkbenchRunAttachmentRequest;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Workbench Run 的 Owner-scoped HTTP 与可恢复 SSE 边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping(path = "/api/workbenches/{workbenchId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class WorkbenchRunController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_RUN_LIST_LIMIT = 20;
    private static final int DEFAULT_EVENT_PAGE_LIMIT = 200;

    private final WorkbenchRunAppService appService;
    private final WorkbenchStageRunAppService stageRunAppService;
    private final WorkbenchRunHistoryAppService historyAppService;
    private final CurrentUserProvider currentUserProvider;
    private final WorkbenchStageCommandQueryService stageCommandQueryService;

    public WorkbenchRunController(
            WorkbenchRunAppService appService,
            WorkbenchStageRunAppService stageRunAppService,
            WorkbenchRunHistoryAppService historyAppService,
            CurrentUserProvider currentUserProvider,
            WorkbenchStageCommandQueryService stageCommandQueryService) {
        this.appService = appService;
        this.stageRunAppService = stageRunAppService;
        this.historyAppService = historyAppService;
        this.currentUserProvider = currentUserProvider;
        this.stageCommandQueryService = stageCommandQueryService;
    }

    @GetMapping("/stages/{stageInstanceIdentifier}/commands")
    public List<CommandDto> listStageCommands(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("stageInstanceIdentifier")
            String stageInstanceIdentifier) {
        List<WorkbenchStageCommandView> commands =
                stageCommandQueryService.list(
                        currentOwner(), WorkbenchId.of(workbenchId),
                        stageInstanceIdentifier);
        return commands.stream()
                .map(command -> new CommandDto(
                        command.getIdentifier(), command.getDescription(),
                        command.getArgumentHint()))
                .collect(java.util.stream.Collectors.toList());
    }

    @PostMapping("/stages/{stageInstanceIdentifier}/runs")
    public ResponseEntity<WorkbenchStageRunSubmissionResult> submit(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("stageInstanceIdentifier")
            String stageInstanceIdentifier,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") long expectedVersion,
            @Valid @RequestBody SubmitWorkbenchRunRequest request) {
        WorkbenchId id = WorkbenchId.of(workbenchId);
        SubmitWorkbenchStageRunCommand command =
                SubmitWorkbenchStageRunCommand.fromExternal(
                        id, stageInstanceIdentifier, expectedVersion,
                        idempotencyKey, request.getMessage(),
                        request.getRunMode(),
                        attachments(request.getAttachments()));
        WorkbenchStageRunSubmissionResult result = stageRunAppService.submit(
                currentOwner(), command);
        return ResponseEntity.accepted()
                .location(URI.create(
                        "/api/workbenches/" + id.getValue()
                                + "/runs/" + result.getRunId()))
                .body(result);
    }

    @GetMapping("/runs")
    public WorkbenchRunListPage list(
            @PathVariable("workbenchId") String workbenchId,
            @RequestParam(value = "stageInstanceIdentifier", required = false)
                    String stageInstanceIdentifier,
            @RequestParam(value = "cursorCreatedAt", required = false)
                    Long cursorCreatedAt,
            @RequestParam(value = "cursorRunId", required = false)
                    String cursorRunId,
            @RequestParam(value = "limit",
                    defaultValue = "" + DEFAULT_RUN_LIST_LIMIT)
                    int limit) {
        return historyAppService.list(
                currentOwner(), WorkbenchId.of(workbenchId),
                new WorkbenchRunListRequest(
                        stageInstanceIdentifier,
                        parseListCursor(cursorCreatedAt, cursorRunId),
                        limit));
    }

    @GetMapping("/runs/{runId}")
    public WorkbenchRunDetailView find(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("runId") String runId) {
        return historyAppService.detail(
                currentOwner(), WorkbenchId.of(workbenchId), runId);
    }

    @GetMapping("/runs/{runId}/events-page")
    public WorkbenchRunEventPage eventPage(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("runId") String runId,
            @RequestParam(value = "after", defaultValue = "0")
                    long after,
            @RequestParam(value = "limit",
                    defaultValue = "" + DEFAULT_EVENT_PAGE_LIMIT)
                    int limit) {
        return historyAppService.events(
                currentOwner(), WorkbenchId.of(workbenchId), runId,
                new WorkbenchRunEventPageRequest(after, limit));
    }

    @GetMapping("/runs/{runId}/capability")
    public WorkbenchRunCapabilityView capability(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("runId") String runId) {
        return historyAppService.capability(
                currentOwner(), WorkbenchId.of(workbenchId), runId);
    }

    @PostMapping("/runs/{runId}/stop")
    public ResponseEntity<WorkbenchRunStopResult> stop(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("runId") String runId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                appService.stop(
                        currentOwner(), WorkbenchId.of(workbenchId), runId));
    }

    @GetMapping(value = "/runs/{runId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("runId") String runId,
            @RequestHeader(value = "Last-Event-ID", required = false)
                    String lastEventId,
            @RequestParam(value = "after", required = false) Long after,
            HttpServletResponse response) {
        long cursor = resolveCursor(lastEventId, after);
        response.setHeader(
                HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        final SseEmitter emitter = createEmitter();
        final EmitterSink sink = new EmitterSink(emitter);
        emitter.onCompletion(sink::disconnect);
        emitter.onTimeout(sink::disconnect);
        emitter.onError(error -> sink.disconnect());
        WorkbenchRunStreamHandle handle;
        try {
            handle = appService.subscribe(
                    currentOwner(), WorkbenchId.of(workbenchId), runId,
                    cursor, sink);
        } catch (WorkbenchRunCursorExpiredException failure) {
            writeExpiredCursor(response, failure);
            return null;
        }
        sink.attach(handle);
        return emitter;
    }

    SseEmitter createEmitter() {
        return new SseEmitter(-1L);
    }

    private OwnerReference currentOwner() {
        return OwnerReference.of(
                currentUserProvider.currentUserId(),
                currentUserProvider.currentUserName());
    }

    private WorkbenchRunListCursor parseListCursor(
            Long createdAt, String runId) {
        if (createdAt == null && runId == null) {
            return null;
        }
        if (createdAt == null || runId == null
                || runId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "cursorCreatedAt and cursorRunId must be provided together");
        }
        return new WorkbenchRunListCursor(
                createdAt.longValue(), runId);
    }

    private List<WorkbenchRunAttachmentReference> attachments(
            List<WorkbenchRunAttachmentRequest> requests) {
        List<WorkbenchRunAttachmentReference> result =
                new ArrayList<WorkbenchRunAttachmentReference>(
                        requests.size());
        for (WorkbenchRunAttachmentRequest request : requests) {
            result.add(request.toReference());
        }
        return result;
    }

    private long resolveCursor(String lastEventId, Long after) {
        if (lastEventId != null && !lastEventId.trim().isEmpty()) {
            try {
                return Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        "invalid Last-Event-ID");
            }
        }
        return after == null ? 0L : after.longValue();
    }

    private void writeExpiredCursor(
            HttpServletResponse response,
            WorkbenchRunCursorExpiredException failure) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "WORKBENCH_RUN_CURSOR_EXPIRED");
        body.put("runId", failure.getRunId());
        body.put("earliestRetainedSeq",
                failure.getEarliestRetainedSeq());
        body.put("lastEventSeq", failure.getLastEventSeq());
        body.put("message", failure.getMessage());
        response.setStatus(HttpStatus.GONE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            MAPPER.writeValue(response.getWriter(), body);
        } catch (IOException ioFailure) {
            throw new IllegalStateException(
                    "could not write expired Workbench Run cursor response",
                    ioFailure);
        }
    }

    private static final class EmitterSink
            implements WorkbenchRunStreamSink {

        private final SseEmitter emitter;
        private final AtomicReference<WorkbenchRunStreamHandle>
                handleReference =
                new AtomicReference<WorkbenchRunStreamHandle>();
        private final AtomicBoolean disconnected =
                new AtomicBoolean(false);

        private EmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(WorkbenchRunEvent event) {
            if (disconnected.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.getSequence()))
                        .name(event.getEventType())
                        .data(event.getPayload()));
            } catch (IOException failure) {
                disconnect();
            }
        }

        @Override
        public void ping() {
            if (disconnected.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name("ping").data(""));
            } catch (IOException failure) {
                disconnect();
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

        private void attach(WorkbenchRunStreamHandle handle) {
            handleReference.set(handle);
            if (disconnected.get()) {
                handle.close();
            }
        }

        private void disconnect() {
            if (!disconnected.compareAndSet(false, true)) {
                return;
            }
            WorkbenchRunStreamHandle handle = handleReference.get();
            if (handle != null) {
                handle.close();
            }
        }
    }
}
