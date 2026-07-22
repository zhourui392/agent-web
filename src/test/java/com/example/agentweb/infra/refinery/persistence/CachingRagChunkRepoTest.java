package com.example.agentweb.infra.refinery.persistence;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.refinery.ArchiveReason;
import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.RefinedContent;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.domain.refinery.TtlCategory;
import com.example.agentweb.infra.refinery.config.RefineryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 快照缓存装饰器 (设计方案 §B4): 召回读路径零 SQL, 写路径失效重载,
 * TTL 到期靠读时过滤, 超软上限自动回落直查. Infra 轻量集成, 真实 SQLite, 不起 Spring.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
class CachingRagChunkRepoTest {

    private static final Instant NOW = Instant.parse("2026-07-02T10:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteRagChunkRepo delegate;
    private RefineryProperties props;
    private CachingRagChunkRepo repo;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("cache-test.db"));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE chat_rag_chunk ("
                + "id TEXT PRIMARY KEY,"
                + "source_session_id TEXT NOT NULL,"
                + "source_msg_range TEXT,"
                + "title TEXT NOT NULL,"
                + "trigger_signals TEXT,"
                + "context TEXT,"
                + "process TEXT,"
                + "conclusion TEXT,"
                + "ttl_category TEXT NOT NULL,"
                + "score REAL NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "expires_at INTEGER,"
                + "archived_at INTEGER,"
                + "agent_type TEXT NOT NULL,"
                + "embedding_model TEXT NOT NULL,"
                + "embedding BLOB NOT NULL,"
                + "source_type TEXT NOT NULL DEFAULT 'CHAT',"
                + "tier TEXT NOT NULL DEFAULT 'EXPLORATORY',"
                + "env TEXT NOT NULL DEFAULT 'unknown',"
                + "detail_path TEXT,"
                + "archive_reason TEXT,"
                + "trigger_description TEXT,"
                + "inject_count INTEGER NOT NULL DEFAULT 0,"
                + "adopt_count INTEGER NOT NULL DEFAULT 0)");
        delegate = spy(new SqliteRagChunkRepo(jdbc));
        props = new RefineryProperties();
        repo = new CachingRagChunkRepo(delegate, props);
    }

    @Test
    void findActive_isServedFromSnapshot_untilWriteInvalidates() {
        repo.save(chunk("c1", null));
        assertEquals(1, repo.findActive(NOW).size());

        insertRowBypassingRepo("c-ghost");

        assertEquals(1, repo.findActive(NOW).size(),
                "绕过 repo 的写不可见 = 读路径确实走快照而非 SQL");

        repo.save(chunk("c2", null));

        assertEquals(3, repo.findActive(NOW).size(),
                "save 应失效快照, 重载后连 ghost 一起可见");
    }

    @Test
    void markArchived_invalidatesSnapshot() {
        repo.save(chunk("c1", null));
        repo.save(chunk("c2", null));
        assertEquals(2, repo.findActive(NOW).size());

        repo.markArchived("c1", NOW, ArchiveReason.NEGATIVE_VERDICT);

        assertEquals(1, repo.findActive(NOW).size());
        assertEquals("c2", repo.findActive(NOW).get(0).getId());
    }

    @Test
    void ttlExpiry_isFilteredAtReadTime_withoutAnyWrite() {
        repo.save(chunk("c-short", NOW.plusSeconds(10)));
        repo.save(chunk("c-long", NOW.plusSeconds(86400)));

        assertEquals(2, repo.findActive(NOW).size());
        assertEquals(1, repo.findActive(NOW.plusSeconds(11)).size(),
                "快照内 chunk 到期应被读时过滤, 无需写失效");
        assertEquals("c-long", repo.findActive(NOW.plusSeconds(11)).get(0).getId());
    }

    @Test
    void cacheDisabled_fallsBackToDirectQuery() {
        props.getRecall().setCacheEnabled(false);
        repo.save(chunk("c1", null));
        assertEquals(1, repo.findActive(NOW).size());

        insertRowBypassingRepo("c-ghost");

        assertEquals(2, repo.findActive(NOW).size(), "关缓存后每次读都应直查");
    }

    @Test
    void overSoftCap_bypassesCache() {
        props.getRecall().setCacheMaxChunks(1);
        repo.save(chunk("c1", null));
        repo.save(chunk("c2", null));
        assertEquals(2, repo.findActive(NOW).size());

        insertRowBypassingRepo("c-ghost");

        assertEquals(3, repo.findActive(NOW).size(),
                "超软上限不缓存, 绕过写立即可见 = 已回落直查");
        verify(delegate, times(2)).findActiveLimited(eq(NOW), eq(2));
        verify(delegate, times(2)).findActive(NOW);
    }

    @Test
    void archiveExpiredBefore_invalidatesSnapshot() {
        repo.save(chunk("c-short", NOW.plusSeconds(10)));
        assertEquals(1, repo.findActive(NOW).size());

        repo.archiveExpiredBefore(NOW.plusSeconds(60));

        assertEquals(0, repo.findActive(NOW.plusSeconds(61)).size());
        assertEquals(0, repo.findActive(NOW).size(), "已归档 chunk 任何时点都不可召回");
    }

    private void insertRowBypassingRepo(String id) {
        jdbc.update("INSERT INTO chat_rag_chunk (id, source_session_id, title, ttl_category, score, "
                        + "created_at, agent_type, embedding_model, embedding) "
                        + "VALUES (?, 's-ghost', 'ghost', 'GENERAL', 0.5, ?, 'CLAUDE', 'm', ?)",
                id, NOW.toEpochMilli(), EmbeddingCodec.encode(new float[]{0.1f}));
    }

    private RagChunk chunk(String id, Instant expiresAt) {
        return RagChunk.builder()
                .id(id)
                .sourceSessionId("s1")
                .agentType(AgentType.CLAUDE)
                .content(new RefinedContent("标题", Collections.singletonList("sig"), "c", "p", "x"))
                .score(0.8)
                .ttlCategory(TtlCategory.GENERAL)
                .createdAt(NOW.minusSeconds(60))
                .expiresAt(expiresAt)
                .embeddingModel("m")
                .embedding(new float[]{0.1f})
                .sourceType(SourceType.CHAT)
                .tier(TrustTier.EXPLORATORY)
                .env("test")
                .build();
    }
}
