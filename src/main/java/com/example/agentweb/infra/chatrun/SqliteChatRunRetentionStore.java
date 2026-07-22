package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ChatRunRetentionStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * SQLite maintenance adapter for expired terminal ChatRun metadata.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Repository
public class SqliteChatRunRetentionStore implements ChatRunRetentionStore {

    private final JdbcTemplate jdbc;

    public SqliteChatRunRetentionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteTerminalRunsBefore(Instant cutoff, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("run retention limit must be positive");
        }
        return jdbc.update("DELETE FROM chat_run WHERE rowid IN ("
                        + "SELECT r.rowid FROM chat_run r "
                        + "WHERE r.status NOT IN ('PENDING','RUNNING','CANCEL_REQUESTED') "
                        + "AND r.updated_at<? AND NOT EXISTS ("
                        + "SELECT 1 FROM chat_run_event e WHERE e.run_id=r.id) "
                        + "ORDER BY r.updated_at ASC LIMIT ?)",
                cutoff.toEpochMilli(), limit);
    }
}
