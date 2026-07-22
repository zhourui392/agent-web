package com.example.agentweb.domain.chatrun;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ChatRunActivityGuardTest {

    @Test
    void requireInactive_should_reject_session_with_active_run() {
        ChatRun active = ChatRun.submit(ChatRunId.of("run-1"), "session-1", 1L,
                "key-1", Instant.parse("2026-07-22T10:00:00Z"));
        ChatRunActivityGuard guard = new RepositoryChatRunActivityGuard(
                new StubChatRunRepository(Optional.of(active)));

        assertThrows(ActiveChatRunExistsException.class,
                () -> guard.requireInactive("session-1"));
    }

    @Test
    void requireInactive_should_allow_session_without_active_run() {
        ChatRunActivityGuard guard = new RepositoryChatRunActivityGuard(
                new StubChatRunRepository(Optional.<ChatRun>empty()));

        assertDoesNotThrow(() -> guard.requireInactive("session-1"));
    }

    @Test
    void requireInactive_should_reject_legacy_execution_not_yet_representedByChatRun() {
        ChatExecutionActivityProbe legacyExecution = sessionId -> true;
        ChatRunActivityGuard guard = new RepositoryChatRunActivityGuard(
                new StubChatRunRepository(Optional.<ChatRun>empty()), legacyExecution);

        assertThrows(ActiveChatRunExistsException.class,
                () -> guard.requireInactive("session-1"));
    }

    private static final class StubChatRunRepository implements ChatRunRepository {

        private final Optional<ChatRun> active;

        private StubChatRunRepository(Optional<ChatRun> active) {
            this.active = active;
        }

        @Override
        public void add(ChatRun run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(ChatRun run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ChatRun> findById(ChatRunId id) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatRun> findBySessionAndIdempotencyKey(String sessionId,
                                                                 String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Optional<ChatRun> findActiveBySessionId(String sessionId) {
            return active;
        }
    }
}
