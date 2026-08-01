package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.domain.chatrun.ChatRunId;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * ChatRun RuntimeHandle 绑定的 SQLite 实现。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteChatRunRuntimeHandleStore
        implements ChatRunRuntimeHandleStore {

    private static final String BINDING_CONFLICT_MESSAGE =
            "chat run runtime handle binding conflicts with persisted state";

    private final JdbcTemplate jdbc;

    public SqliteChatRunRuntimeHandleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bind(ChatRunId runId, RuntimeHandle handle, Instant boundAt) {
        requireBinding(runId, handle, boundAt);
        try {
            jdbc.update("INSERT INTO chat_run_runtime_handle "
                            + "(run_id, execution_id, handle_id, bound_at) "
                            + "VALUES (?,?,?,?)",
                    runId.getValue(), handle.getExecutionId(), handle.getHandleId(),
                    boundAt.toEpochMilli());
        } catch (DataAccessException failure) {
            if (!isSqliteConstraint(failure)) {
                throw failure;
            }
            if (find(runId).filter(handle::equals).isPresent()) {
                return;
            }
            throw new IllegalStateException(BINDING_CONFLICT_MESSAGE);
        }
    }

    @Override
    public Optional<RuntimeHandle> find(ChatRunId runId) {
        if (runId == null) {
            throw new IllegalArgumentException("chat run id must not be null");
        }
        List<RuntimeHandle> handles = jdbc.query(
                "SELECT execution_id, handle_id FROM chat_run_runtime_handle "
                        + "WHERE run_id=?",
                (resultSet, rowNumber) -> new RuntimeHandle(
                        resultSet.getString("execution_id"),
                        resultSet.getString("handle_id")),
                runId.getValue());
        return handles.isEmpty()
                ? Optional.<RuntimeHandle>empty()
                : Optional.of(handles.get(0));
    }

    @Override
    public void delete(ChatRunId runId) {
        if (runId == null) {
            throw new IllegalArgumentException("chat run id must not be null");
        }
        jdbc.update("DELETE FROM chat_run_runtime_handle WHERE run_id=?",
                runId.getValue());
    }

    private void requireBinding(
            ChatRunId runId, RuntimeHandle handle, Instant boundAt) {
        if (runId == null || handle == null || boundAt == null) {
            throw new IllegalArgumentException(
                    "chat run runtime handle binding values must not be null");
        }
        if (!runId.getValue().equals(handle.getExecutionId())) {
            throw new IllegalArgumentException(
                    "chat run id must equal runtime execution id");
        }
    }

    private boolean isSqliteConstraint(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLiteException) {
                SQLiteException sqliteFailure = (SQLiteException) current;
                return (sqliteFailure.getResultCode().code & 0xff)
                        == SQLiteErrorCode.SQLITE_CONSTRAINT.code;
            }
            current = current.getCause();
        }
        return false;
    }
}
