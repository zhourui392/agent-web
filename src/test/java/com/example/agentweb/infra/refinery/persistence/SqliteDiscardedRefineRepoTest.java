package com.example.agentweb.infra.refinery.persistence;

import com.example.agentweb.domain.refinery.DiscardedRefineRecord;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TtlCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-04
 */
public class SqliteDiscardedRefineRepoTest {

    private static final Instant T0 = Instant.parse("2026-06-04T10:00:00Z");

    @TempDir
    Path tempDir;

    private SqliteDiscardedRefineRepo repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("discarded-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE chat_rag_discarded ("
                        + "id TEXT PRIMARY KEY,"
                        + "source_type TEXT NOT NULL DEFAULT 'CHAT',"
                        + "source_session_id TEXT NOT NULL,"
                        + "title TEXT NOT NULL,"
                        + "conclusion TEXT,"
                        + "ttl_category TEXT,"
                        + "score REAL NOT NULL,"
                        + "threshold REAL NOT NULL,"
                        + "agent_type TEXT,"
                        + "env TEXT,"
                        + "created_at INTEGER NOT NULL,"
                        + "reason TEXT NOT NULL DEFAULT 'score below threshold')"
        );
        repo = new SqliteDiscardedRefineRepo(jdbc);
    }

    private DiscardedRefineRecord.Builder base(String id, Instant createdAt) {
        return DiscardedRefineRecord.builder()
                .id(id)
                .sourceType(SourceType.CHAT)
                .sourceSessionId("sess-" + id)
                .title("低分会话 " + id)
                .conclusion("结论 " + id)
                .ttlCategory(TtlCategory.GENERAL)
                .score(0.3d)
                .threshold(0.5d)
                .agentType("CLAUDE")
                .env("test")
                .createdAt(createdAt)
                .reason(DiscardedRefineRecord.REASON_BELOW_THRESHOLD);
    }

    @Test
    public void save_should_persist_all_fields() {
        repo.save(base("a", T0).build());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM chat_rag_discarded WHERE id = ?", "a");
        assertEquals("CHAT", row.get("source_type"));
        assertEquals("sess-a", row.get("source_session_id"));
        assertEquals("低分会话 a", row.get("title"));
        assertEquals("结论 a", row.get("conclusion"));
        assertEquals("GENERAL", row.get("ttl_category"));
        assertEquals(0.3d, ((Number) row.get("score")).doubleValue(), 1e-9);
        assertEquals(0.5d, ((Number) row.get("threshold")).doubleValue(), 1e-9);
        assertEquals("CLAUDE", row.get("agent_type"));
        assertEquals("test", row.get("env"));
        assertEquals(T0.toEpochMilli(), ((Number) row.get("created_at")).longValue());
        assertEquals(DiscardedRefineRecord.REASON_BELOW_THRESHOLD, row.get("reason"));
    }

    @Test
    public void save_with_null_optional_fields_should_persist_as_null() {
        repo.save(DiscardedRefineRecord.builder()
                .id("nullable")
                .sourceType(SourceType.DIAGNOSE)
                .sourceSessionId("task-1")
                .title("仅标题")
                .score(0.1d)
                .threshold(0.5d)
                .createdAt(T0)
                .build());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM chat_rag_discarded WHERE id = ?", "nullable");
        assertNull(row.get("conclusion"));
        assertNull(row.get("ttl_category"));
        assertNull(row.get("agent_type"));
        assertNull(row.get("env"));
        assertEquals("DIAGNOSE", row.get("source_type"));
        assertEquals(DiscardedRefineRecord.REASON_BELOW_THRESHOLD, row.get("reason"));
    }

    @Test
    public void delete_by_id_should_hard_delete_target_only() {
        repo.save(base("a", T0).build());
        repo.save(base("b", T0.plusSeconds(1)).build());

        assertTrue(repo.deleteById("a"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_rag_discarded", Integer.class));
        assertEquals("b", jdbc.queryForObject("SELECT id FROM chat_rag_discarded", String.class));
    }

    @Test
    public void delete_by_id_for_missing_id_should_return_false() {
        assertFalse(repo.deleteById("missing"));
    }
}
