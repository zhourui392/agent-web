package com.example.agentweb.interfaces;

import com.example.agentweb.app.chatrun.EventCursorExpiredException;
import com.example.agentweb.app.chatrun.InvalidIdempotencyKeyException;
import com.example.agentweb.app.chatrun.RunCapacityExceededException;
import com.example.agentweb.app.harness.InvalidHarnessIdempotencyKeyException;
import com.example.agentweb.domain.auth.UsernameAlreadyExistsException;
import com.example.agentweb.domain.chat.ChatSessionNotFoundException;
import com.example.agentweb.domain.chat.SessionDeletionForbiddenException;
import com.example.agentweb.domain.chatrun.ActiveChatRunExistsException;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.IllegalHarnessTransitionException;
import com.example.agentweb.domain.harness.DuplicateHarnessRunException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * @author zhourui(V33215020)
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleUsernameAlreadyExists(
            UsernameAlreadyExistsException ex) {
        Map<String, Object> body = new HashMap<>(2);
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ChatSessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleChatSessionNotFound(
            ChatSessionNotFoundException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "SESSION_NOT_FOUND");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidIdempotencyKey(
            InvalidIdempotencyKeyException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "INVALID_IDEMPOTENCY_KEY");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ChatRunNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleChatRunNotFound(ChatRunNotFoundException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "CHAT_RUN_NOT_FOUND");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(HarnessRunNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleHarnessRunNotFound(HarnessRunNotFoundException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "HARNESS_RUN_NOT_FOUND");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(InvalidHarnessIdempotencyKeyException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidHarnessIdempotencyKey(
            InvalidHarnessIdempotencyKeyException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "INVALID_HARNESS_IDEMPOTENCY_KEY");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalHarnessTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalHarnessTransition(
            IllegalHarnessTransitionException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "HARNESS_ILLEGAL_TRANSITION");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(DuplicateHarnessRunException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateHarnessRun(
            DuplicateHarnessRunException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "HARNESS_IDEMPOTENCY_CONFLICT");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ActiveChatRunExistsException.class)
    public ResponseEntity<Map<String, Object>> handleActiveChatRunExists(ActiveChatRunExistsException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "CHAT_RUN_ALREADY_ACTIVE");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(EventCursorExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleExpiredCursor(EventCursorExpiredException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "EVENT_CURSOR_EXPIRED");
        body.put("runId", ex.getRunId());
        body.put("earliestRetainedSeq", ex.getEarliestRetainedSeq());
        body.put("lastEventSeq", ex.getLastEventSeq());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GONE).body(body);
    }

    @ExceptionHandler(RunCapacityExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRunCapacityExceeded(
            RunCapacityExceededException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("code", "RUN_CAPACITY_EXCEEDED");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        Map<String, Object> body = new HashMap<>(16);
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new HashMap<>(16);
        body.put("error", "validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        Map<String, Object> body = new HashMap<>(16);
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(SessionDeletionForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleSessionDeletionForbidden(
            SessionDeletionForbiddenException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
