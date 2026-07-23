package com.example.agentweb.infra.refinery;

import com.example.agentweb.app.refinery.DiscardedRefinePage;
import com.example.agentweb.app.refinery.RefineryChunkPage;
import com.example.agentweb.app.refinery.RefineryChunkView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author alex
 * @since 2026-07-23
 */
class SqliteRefineryAdminQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteRefineryAdminQueryService service;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("refinery-query.db"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE chat_rag_chunk ("
                + "id TEXT PRIMARY KEY, source_session_id TEXT NOT NULL, source_msg_range TEXT, "
                + "title TEXT NOT NULL, trigger_signals TEXT, conclusion TEXT, ttl_category TEXT NOT NULL, "
                + "score REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER, archived_at INTEGER, "
                + "agent_type TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE chat_rag_discarded ("
                + "id TEXT PRIMARY KEY, source_type TEXT NOT NULL, source_session_id TEXT NOT NULL, "
                + "title TEXT NOT NULL, conclusion TEXT, ttl_category TEXT, score REAL NOT NULL, "
                + "threshold REAL NOT NULL, agent_type TEXT, env TEXT, created_at INTEGER NOT NULL, reason TEXT)");
        service = new SqliteRefineryAdminQueryService(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void findChunks_should_clamp_page_and_size_and_project_status() {
        insertChunk("active", NOW.plusSeconds(60), null, NOW.minusSeconds(10));
        insertChunk("expired", NOW.minusSeconds(1), null, NOW.minusSeconds(20));
        insertChunk("archived", null, NOW.minusSeconds(2), NOW.minusSeconds(30));

        jdbc.update("UPDATE chat_rag_chunk SET trigger_signals = NULL WHERE id = ?", "archived");

        RefineryChunkPage page = service.findChunks(0, 999, "all");

        assertEquals(1, page.page());
        assertEquals(100, page.size());
        assertEquals(3L, page.total());
        assertEquals("active", page.items().get(0).id());
        assertEquals(RefineryChunkView.STATUS_ACTIVE, page.items().get(0).status());
        assertEquals(RefineryChunkView.STATUS_ARCHIVED, page.items().get(1).status());
        assertEquals(RefineryChunkView.STATUS_ARCHIVED, page.items().get(2).status());
        assertEquals(0, page.items().get(2).triggerSignals().size());
    }

    @Test
    void findChunks_activeOnly_should_exclude_expired_and_archived() {
        insertChunk("active", NOW.plusSeconds(60), null, NOW);
        insertChunk("expired", NOW.minusSeconds(1), null, NOW.minusSeconds(1));
        insertChunk("archived", null, NOW.minusSeconds(2), NOW.minusSeconds(2));

        RefineryChunkPage page = service.findChunks(1, 20, "ACTIVE");

        assertEquals(1L, page.total());
        assertEquals("active", page.items().get(0).id());
    }

    @Test
    void findDiscarded_should_clamp_and_project_without_domain_aggregate() {
        jdbc.update("INSERT INTO chat_rag_discarded VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "d1", "CHAT", "s1", "低分标题", "结论", "GENERAL", 0.3d, 0.5d,
                "CLAUDE", "test", NOW.toEpochMilli(), "score below threshold");

        DiscardedRefinePage page = service.findDiscarded(-2, 0);

        assertEquals(1, page.page());
        assertEquals(1, page.size());
        assertEquals(1L, page.total());
        assertEquals("d1", page.items().get(0).id());
        assertEquals("DISCARDED", page.items().get(0).status());
    }

    private void insertChunk(String id, Instant expiresAt, Instant archivedAt, Instant createdAt) {
        jdbc.update("INSERT INTO chat_rag_chunk VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, "session-1", "m1..m2", "标题", "[\"信号1\"]", "结论", "BUSINESS", 0.8d,
                createdAt.toEpochMilli(), epoch(expiresAt), epoch(archivedAt), "CLAUDE");
    }

    private Long epoch(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
