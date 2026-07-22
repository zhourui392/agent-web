package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunEventDraft;
import com.example.agentweb.app.chatrun.ChatRunEventStore;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.EventSequenceRange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite append-only event store for resumable chat streams.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Repository
public class SqliteChatRunEventStore implements ChatRunEventStore {

    private final JdbcTemplate jdbc;

    public SqliteChatRunEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChatRunEvent> appendAssigned(ChatRunId runId, EventSequenceRange range,
                                             List<ChatRunEventDraft> drafts, Instant createdAt) {
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("event batch must not be empty");
        }
        long expectedSize = range.getEndInclusive() - range.getStartInclusive() + 1L;
        if (expectedSize != drafts.size()) {
            throw new IllegalArgumentException("event sequence range does not match batch size");
        }
        List<ChatRunEvent> persisted = new ArrayList<ChatRunEvent>(drafts.size());
        long sequence = range.getStartInclusive();
        for (ChatRunEventDraft draft : drafts) {
            int payloadSize = draft.getPayload().getBytes(StandardCharsets.UTF_8).length;
            jdbc.update("INSERT INTO chat_run_event "
                            + "(run_id, seq, event_type, payload, payload_size, created_at) VALUES (?,?,?,?,?,?)",
                    runId.getValue(), sequence, draft.getEventType(), draft.getPayload(),
                    payloadSize, createdAt.toEpochMilli());
            persisted.add(new ChatRunEvent(runId, sequence, draft.getEventType(), draft.getPayload(),
                    payloadSize, createdAt));
            sequence++;
        }
        return persisted;
    }

    @Override
    public List<ChatRunEvent> findAfterThrough(ChatRunId runId, long afterExclusive,
                                              long throughInclusive, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("event replay limit must be positive");
        }
        return jdbc.query("SELECT run_id, seq, event_type, payload, payload_size, created_at "
                        + "FROM chat_run_event WHERE run_id=? AND seq>? AND seq<=? ORDER BY seq ASC LIMIT ?",
                this::map, runId.getValue(), afterExclusive, throughInclusive, limit);
    }

    @Override
    public long findEarliestSequence(ChatRunId runId) {
        Long earliest = jdbc.queryForObject("SELECT MIN(seq) FROM chat_run_event WHERE run_id=?",
                Long.class, runId.getValue());
        return earliest == null ? 0L : earliest.longValue();
    }

    @Override
    public int deleteBefore(Instant cutoff, int limit) {
        return jdbc.update("DELETE FROM chat_run_event WHERE rowid IN ("
                        + "SELECT e.rowid FROM chat_run_event e JOIN chat_run r ON r.id=e.run_id "
                        + "WHERE e.created_at<? AND r.status NOT IN ('PENDING','RUNNING','CANCEL_REQUESTED') "
                        + "ORDER BY e.created_at ASC LIMIT ?)",
                cutoff.toEpochMilli(), limit);
    }

    private ChatRunEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new ChatRunEvent(ChatRunId.of(rs.getString("run_id")), rs.getLong("seq"),
                rs.getString("event_type"), rs.getString("payload"), rs.getInt("payload_size"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }
}
