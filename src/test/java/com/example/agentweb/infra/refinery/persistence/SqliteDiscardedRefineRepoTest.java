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
import java.util.List;

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

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("discarded-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
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
    public void save_then_find_page_should_round_trip_all_fields() {
        repo.save(base("a", T0).build());

        List<DiscardedRefineRecord> page = repo.findPage(0, 10);
        assertEquals(1, page.size());
        DiscardedRefineRecord got = page.get(0);
        assertEquals("a", got.getId());
        assertEquals(SourceType.CHAT, got.getSourceType());
        assertEquals("sess-a", got.getSourceSessionId());
        assertEquals("低分会话 a", got.getTitle());
        assertEquals("结论 a", got.getConclusion());
        assertEquals(TtlCategory.GENERAL, got.getTtlCategory());
        assertEquals(0.3d, got.getScore(), 1e-9);
        assertEquals(0.5d, got.getThreshold(), 1e-9);
        assertEquals("CLAUDE", got.getAgentType());
        assertEquals("test", got.getEnv());
        assertEquals(T0, got.getCreatedAt());
        assertEquals(DiscardedRefineRecord.REASON_BELOW_THRESHOLD, got.getReason());
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

        DiscardedRefineRecord got = repo.findPage(0, 10).get(0);
        assertNull(got.getConclusion());
        assertNull(got.getTtlCategory());
        assertNull(got.getAgentType());
        assertNull(got.getEnv());
        assertEquals(SourceType.DIAGNOSE, got.getSourceType());
        // reason 缺省应回退到默认值
        assertEquals(DiscardedRefineRecord.REASON_BELOW_THRESHOLD, got.getReason());
    }

    @Test
    public void find_page_should_order_by_created_at_desc_and_paginate() {
        repo.save(base("old", T0).build());
        repo.save(base("mid", T0.plusSeconds(60)).build());
        repo.save(base("new", T0.plusSeconds(120)).build());

        List<DiscardedRefineRecord> first = repo.findPage(0, 2);
        assertEquals(2, first.size());
        assertEquals("new", first.get(0).getId());
        assertEquals("mid", first.get(1).getId());

        List<DiscardedRefineRecord> second = repo.findPage(2, 2);
        assertEquals(1, second.size());
        assertEquals("old", second.get(0).getId());
    }

    @Test
    public void count_should_tally_all_rows() {
        assertEquals(0L, repo.count());
        repo.save(base("a", T0).build());
        repo.save(base("b", T0.plusSeconds(1)).build());
        assertEquals(2L, repo.count());
    }

    @Test
    public void delete_by_id_should_hard_delete_target_only() {
        repo.save(base("a", T0).build());
        repo.save(base("b", T0.plusSeconds(1)).build());

        assertTrue(repo.deleteById("a"));
        assertEquals(1L, repo.count());
        assertEquals("b", repo.findPage(0, 10).get(0).getId());
    }

    @Test
    public void delete_by_id_for_missing_id_should_return_false() {
        assertFalse(repo.deleteById("missing"));
    }
}
