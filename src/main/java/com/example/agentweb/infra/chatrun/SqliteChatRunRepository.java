package com.example.agentweb.infra.chatrun;

import com.example.agentweb.domain.chatrun.ActiveChatRunExistsException;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.chatrun.DuplicateChatRunSubmissionException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SQLite write-side repository for ChatRun aggregates.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Repository
public class SqliteChatRunRepository implements ChatRunRepository {

    private static final String COLUMNS = "id, session_id, user_message_id, assistant_message_id, "
            + "idempotency_key, recall_enabled, status, last_event_seq, exit_code, failure_code, error_message, "
            + "created_at, started_at, cancel_requested_at, finished_at, updated_at, version";

    private final JdbcTemplate jdbc;

    public SqliteChatRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(ChatRun run) {
        try {
            jdbc.update("INSERT INTO chat_run (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    run.getId().getValue(), run.getSessionId(), run.getUserMessageId(),
                    run.getAssistantMessageId(), run.getIdempotencyKey(), run.isRecallEnabled() ? 1 : 0,
                    run.getStatus().name(),
                    run.getLastEventSeq(), run.getExitCode(), run.getFailureCode(), run.getErrorMessage(),
                    toMillis(run.getCreatedAt()), toMillis(run.getStartedAt()),
                    toMillis(run.getCancelRequestedAt()), toMillis(run.getFinishedAt()),
                    toMillis(run.getUpdatedAt()), run.getVersion());
        } catch (DataAccessException ex) {
            throw translateInsertFailure(run, ex);
        }
    }

    @Override
    public void update(ChatRun run) {
        long expectedVersion = run.getVersion();
        int rows = jdbc.update("UPDATE chat_run SET assistant_message_id=?, status=?, last_event_seq=?, "
                        + "exit_code=?, failure_code=?, error_message=?, started_at=?, cancel_requested_at=?, "
                        + "finished_at=?, updated_at=?, version=version+1 WHERE id=? AND version=?",
                run.getAssistantMessageId(), run.getStatus().name(), run.getLastEventSeq(),
                run.getExitCode(), run.getFailureCode(), run.getErrorMessage(),
                toMillis(run.getStartedAt()), toMillis(run.getCancelRequestedAt()),
                toMillis(run.getFinishedAt()), toMillis(run.getUpdatedAt()),
                run.getId().getValue(), expectedVersion);
        if (rows != 1) {
            throw new IllegalStateException("stale or missing chat run: " + run.getId().getValue());
        }
        run.synchronizeVersion(expectedVersion + 1L);
    }

    @Override
    public Optional<ChatRun> findById(ChatRunId id) {
        return single("SELECT " + COLUMNS + " FROM chat_run WHERE id = ?", id.getValue());
    }

    @Override
    public Optional<ChatRun> findBySessionAndIdempotencyKey(String sessionId, String idempotencyKey) {
        return single("SELECT " + COLUMNS
                + " FROM chat_run WHERE session_id = ? AND idempotency_key = ?", sessionId, idempotencyKey);
    }

    @Override
    public Optional<ChatRun> findActiveBySessionId(String sessionId) {
        return single("SELECT " + COLUMNS + " FROM chat_run WHERE session_id = ? "
                + "AND status IN ('PENDING','RUNNING','CANCEL_REQUESTED') ORDER BY created_at DESC LIMIT 1",
                sessionId);
    }

    private Optional<ChatRun> single(String sql, Object... arguments) {
        List<ChatRun> found = jdbc.query(sql, this::map, arguments);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private ChatRun map(ResultSet rs, int rowNum) throws SQLException {
        return ChatRun.restore(
                ChatRunId.of(rs.getString("id")),
                rs.getString("session_id"),
                rs.getLong("user_message_id"),
                nullableLong(rs, "assistant_message_id"),
                rs.getString("idempotency_key"),
                rs.getInt("recall_enabled") != 0,
                ChatRunStatus.valueOf(rs.getString("status")),
                rs.getLong("last_event_seq"),
                nullableInteger(rs, "exit_code"),
                rs.getString("failure_code"),
                rs.getString("error_message"),
                fromMillis(rs, "created_at"),
                fromMillis(rs, "started_at"),
                fromMillis(rs, "cancel_requested_at"),
                fromMillis(rs, "finished_at"),
                fromMillis(rs, "updated_at"),
                rs.getLong("version"));
    }

    private RuntimeException translateInsertFailure(ChatRun run, DataAccessException ex) {
        String message = rootMessage(ex);
        if (message.contains("chat_run.session_id, chat_run.idempotency_key")) {
            return new DuplicateChatRunSubmissionException(run.getSessionId(), run.getIdempotencyKey());
        }
        if (message.contains("chat_run.session_id")) {
            return new ActiveChatRunExistsException(run.getSessionId());
        }
        return ex;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Long.valueOf(value);
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : Integer.valueOf(value);
    }

    private Instant fromMillis(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private Long toMillis(Instant value) {
        return value == null ? null : Long.valueOf(value.toEpochMilli());
    }
}
