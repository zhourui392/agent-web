package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessRunEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqliteHarnessEventStore} 轻量集成测试：真实 SQLite + @TempDir，不起 Spring。
 *
 * @author zhourui(V33215020)
 */
class SqliteHarnessEventStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteHarnessEventStore store;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE harness_event ("
                + "run_id TEXT NOT NULL, "
                + "sequence INTEGER NOT NULL, "
                + "event_type TEXT NOT NULL, "
                + "stage TEXT, "
                + "actor TEXT NOT NULL, "
                + "detail TEXT, "
                + "occurred_at INTEGER NOT NULL, "
                + "PRIMARY KEY(run_id, sequence))");
        store = new SqliteHarnessEventStore(jdbc);
    }

    @Test
    void findAfterThrough_should_return_events_in_sequence_order() {
        insertEvent("run-1", 1L, "RUN_CREATED", null, "admin", null);
        insertEvent("run-1", 2L, "STAGE_STARTED", "ANALYSIS", "admin", "start");
        insertEvent("run-1", 3L, "GATE_PASSED", "ANALYSIS", "admin", null);

        List<HarnessRunEvent> events = store.findAfterThrough("run-1", 1L, 3L, 100);

        assertEquals(2, events.size());
        assertEquals(2L, events.get(0).getSequence());
        assertEquals("STAGE_STARTED", events.get(0).getEventType());
        assertEquals("ANALYSIS", events.get(0).getStage());
        assertEquals(3L, events.get(1).getSequence());
        assertEquals("GATE_PASSED", events.get(1).getEventType());
    }

    @Test
    void findAfterThrough_should_respect_limit() {
        for (int i = 1; i <= 10; i++) {
            insertEvent("run-1", (long) i, "EVENT_" + i, null, "admin", null);
        }

        List<HarnessRunEvent> page = store.findAfterThrough("run-1", 0L, 10L, 3);

        assertEquals(3, page.size());
        assertEquals(1L, page.get(0).getSequence());
        assertEquals(3L, page.get(2).getSequence());
    }

    @Test
    void findAfterThrough_should_return_empty_when_no_events_in_range() {
        insertEvent("run-1", 1L, "RUN_CREATED", null, "admin", null);

        List<HarnessRunEvent> events = store.findAfterThrough("run-1", 5L, 10L, 100);

        assertTrue(events.isEmpty());
    }

    @Test
    void findEarliestSequence_should_return_min_sequence() {
        insertEvent("run-1", 3L, "EVENT_3", null, "admin", null);
        insertEvent("run-1", 1L, "EVENT_1", null, "admin", null);
        insertEvent("run-1", 2L, "EVENT_2", null, "admin", null);

        assertEquals(1L, store.findEarliestSequence("run-1"));
    }

    @Test
    void findLastSequence_should_return_max_sequence() {
        insertEvent("run-1", 2L, "EVENT_2", null, "admin", null);
        insertEvent("run-1", 5L, "EVENT_5", null, "admin", null);
        insertEvent("run-1", 1L, "EVENT_1", null, "admin", null);

        assertEquals(5L, store.findLastSequence("run-1"));
    }

    @Test
    void findEarliestSequence_should_return_zero_when_no_events() {
        assertEquals(0L, store.findEarliestSequence("nonexistent-run"));
    }

    @Test
    void findLastSequence_should_return_zero_when_no_events() {
        assertEquals(0L, store.findLastSequence("nonexistent-run"));
    }

    @Test
    void findAfterThrough_should_isolate_by_run_id() {
        insertEvent("run-1", 1L, "RUN_CREATED", null, "admin", null);
        insertEvent("run-2", 1L, "RUN_CREATED", null, "admin", null);
        insertEvent("run-2", 2L, "STAGE_STARTED", "ANALYSIS", "admin", null);

        List<HarnessRunEvent> run1Events = store.findAfterThrough("run-1", 0L, 10L, 100);
        List<HarnessRunEvent> run2Events = store.findAfterThrough("run-2", 0L, 10L, 100);

        assertEquals(1, run1Events.size());
        assertEquals(2, run2Events.size());
    }

    private void insertEvent(String runId, long sequence, String eventType, String stage,
                             String actor, String detail) {
        jdbc.update("INSERT INTO harness_event VALUES (?,?,?,?,?,?,?)",
                runId, sequence, eventType, stage, actor, detail, NOW.toEpochMilli());
    }
}