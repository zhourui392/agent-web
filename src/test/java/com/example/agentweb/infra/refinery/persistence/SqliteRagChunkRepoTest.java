package com.example.agentweb.infra.refinery.persistence;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.RefinedContent;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.domain.refinery.TtlCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public class SqliteRagChunkRepoTest {

    private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteRagChunkRepo repo;

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("refinery-chunk-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE chat_rag_chunk ("
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
                        + "adopt_count INTEGER NOT NULL DEFAULT 0)"
        );
        repo = new SqliteRagChunkRepo(jdbc);
    }

    @Test
    public void trigger_description_should_round_trip_and_telemetry_counters_should_increment() {
        float[] vec = {0.1f, -0.2f};
        RagChunk chunk = RagChunk.builder()
                .id("c-m4")
                .sourceSessionId("sess-m4")
                .agentType(AgentType.CLAUDE)
                .content(new RefinedContent("标题", java.util.List.of("信号"), "下单一直转圈时",
                        "ctx", "p", "c"))
                .score(0.8)
                .ttlCategory(TtlCategory.GENERAL)
                .createdAt(Instant.now())
                .embeddingModel("m")
                .embedding(vec)
                .sourceType(SourceType.CHAT)
                .tier(TrustTier.EXPLORATORY)
                .env("test")
                .build();
        repo.save(chunk);

        assertEquals("下单一直转圈时",
                repo.findById("c-m4").orElseThrow().getContent().getTriggerDescription());

        assertEquals(1, repo.incrementInjectCount(java.util.List.of("c-m4", "missing")));
        assertTrue(repo.incrementAdoptCount("c-m4"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT inject_count FROM chat_rag_chunk WHERE id='c-m4'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT adopt_count FROM chat_rag_chunk WHERE id='c-m4'", Integer.class));

        assertTrue(repo.updateEmbedding("c-m4", new float[]{0.9f, 0.8f}, "m2"));
        RagChunk reloaded = repo.findById("c-m4").orElseThrow();
        assertEquals("m2", reloaded.getEmbeddingModel());
        assertEquals(0.9f, reloaded.getEmbedding()[0], 1e-6);
    }

    @Test
    public void save_then_find_by_id_should_round_trip_all_fields() {
        float[] vec = {0.1f, -0.2f, 0.3f, 0.4f};
        RagChunk chunk = RagChunk.builder()
                .id("c-1")
                .sourceSessionId("sess-1")
                .sourceMsgRange("msg_5..msg_12")
                .agentType(AgentType.CLAUDE)
                .content(new RefinedContent(
                        "标题",
                        Arrays.asList("退款按钮不显示", "Page load error"),
                        "ctx",
                        "1) 复现; 2) 排查; 3) 修复",
                        "下次直接看 X 配置"))
                .score(0.87)
                .ttlCategory(TtlCategory.DEPLOY)
                .createdAt(NOW)
                .expiresAt(NOW.plusSeconds(86400 * 30))
                .embeddingModel("doubao-embedding-vision")
                .embedding(vec)
                .detailPath("docs/issue-log/issue/I-001-xxx.md")
                .build();

        repo.save(chunk);

        Optional<RagChunk> loaded = repo.findById("c-1");
        assertTrue(loaded.isPresent());
        RagChunk got = loaded.get();
        assertEquals("c-1", got.getId());
        assertEquals("sess-1", got.getSourceSessionId());
        assertEquals("msg_5..msg_12", got.getSourceMsgRange());
        assertEquals(AgentType.CLAUDE, got.getAgentType());
        assertEquals("标题", got.getContent().getTitle());
        assertEquals(Arrays.asList("退款按钮不显示", "Page load error"),
                got.getContent().getTriggerSignals());
        assertEquals("ctx", got.getContent().getContext());
        assertEquals("1) 复现; 2) 排查; 3) 修复", got.getContent().getProcess());
        assertEquals("下次直接看 X 配置", got.getContent().getConclusion());
        assertEquals(0.87, got.getScore(), 1e-6);
        assertEquals(TtlCategory.DEPLOY, got.getTtlCategory());
        assertEquals(NOW, got.getCreatedAt());
        assertEquals(NOW.plusSeconds(86400 * 30), got.getExpiresAt());
        assertNull(got.getArchivedAt());
        assertEquals("doubao-embedding-vision", got.getEmbeddingModel());
        assertArrayEquals(vec, got.getEmbedding(), 0f);
        assertEquals("docs/issue-log/issue/I-001-xxx.md", got.getDetailPath());
    }

    @Test
    public void find_by_id_no_match_should_return_empty() {
        assertFalse(repo.findById("missing").isPresent());
    }

    @Test
    public void save_with_null_optional_fields_should_persist_correctly() {
        RagChunk chunk = newMinimalChunk("c-min").build();
        repo.save(chunk);
        RagChunk loaded = repo.findById("c-min").orElseThrow(AssertionError::new);
        assertNull(loaded.getSourceMsgRange());
        assertNull(loaded.getExpiresAt());
        assertNull(loaded.getArchivedAt());
        assertTrue(loaded.getContent().getTriggerSignals().isEmpty());
    }

    @Test
    public void find_active_should_filter_out_archived_and_expired_chunks() {
        // active chunk: archived_at=null, expires_at>now
        repo.save(newMinimalChunk("c-active").expiresAt(NOW.plusSeconds(3600)).build());
        // expired
        repo.save(newMinimalChunk("c-expired").expiresAt(NOW.minusSeconds(60)).build());
        // archived (archived_at != null)
        repo.save(newMinimalChunk("c-archived").archivedAt(NOW.minusSeconds(120)).build());
        // active and never expires (expires_at=null)
        repo.save(newMinimalChunk("c-forever").build());

        List<RagChunk> active = repo.findActive(NOW);

        assertEquals(2, active.size());
        // 按 id 校验, 不假设排序
        List<String> ids = active.stream().map(RagChunk::getId).sorted().collect(java.util.stream.Collectors.toList());
        assertEquals(Arrays.asList("c-active", "c-forever"), ids);
    }

    @Test
    public void find_page_all_should_paginate_by_created_at_desc() {
        repo.save(newMinimalChunk("c1").createdAt(NOW.minusSeconds(40)).build());
        repo.save(newMinimalChunk("c2").createdAt(NOW.minusSeconds(30)).build());
        repo.save(newMinimalChunk("c3").createdAt(NOW.minusSeconds(20)).build());
        repo.save(newMinimalChunk("c4").createdAt(NOW.minusSeconds(10)).build());
        repo.save(newMinimalChunk("c5").createdAt(NOW).build());

        List<RagChunk> page1 = repo.findPage(false, NOW, 0, 2);
        List<RagChunk> page2 = repo.findPage(false, NOW, 2, 2);

        assertEquals(Arrays.asList("c5", "c4"),
                page1.stream().map(RagChunk::getId).collect(java.util.stream.Collectors.toList()));
        assertEquals(Arrays.asList("c3", "c2"),
                page2.stream().map(RagChunk::getId).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    public void find_page_active_only_should_exclude_archived_and_expired() {
        repo.save(newMinimalChunk("active").expiresAt(NOW.plusSeconds(3600)).build());
        repo.save(newMinimalChunk("expired").expiresAt(NOW.minusSeconds(60)).build());
        repo.save(newMinimalChunk("archived").archivedAt(NOW.minusSeconds(120)).build());
        repo.save(newMinimalChunk("forever").build());

        List<RagChunk> active = repo.findPage(true, NOW, 0, 10);

        List<String> ids = active.stream().map(RagChunk::getId).sorted()
                .collect(java.util.stream.Collectors.toList());
        assertEquals(Arrays.asList("active", "forever"), ids);
    }

    @Test
    public void count_should_tally_all_and_active_separately() {
        repo.save(newMinimalChunk("active").expiresAt(NOW.plusSeconds(3600)).build());
        repo.save(newMinimalChunk("expired").expiresAt(NOW.minusSeconds(60)).build());
        repo.save(newMinimalChunk("archived").archivedAt(NOW.minusSeconds(120)).build());
        repo.save(newMinimalChunk("forever").build());

        assertEquals(4L, repo.count(false, NOW));
        assertEquals(2L, repo.count(true, NOW));
    }

    @Test
    public void mark_archived_should_write_timestamp_and_return_true_on_match() {
        repo.save(newMinimalChunk("c-soft-del").build());

        boolean archived = repo.markArchived("c-soft-del", NOW, com.example.agentweb.domain.refinery.ArchiveReason.NEGATIVE_VERDICT);

        assertTrue(archived);
        RagChunk loaded = repo.findById("c-soft-del").orElseThrow(AssertionError::new);
        assertEquals(NOW, loaded.getArchivedAt());
        assertEquals("NEGATIVE_VERDICT", jdbc.queryForObject(
                "SELECT archive_reason FROM chat_rag_chunk WHERE id = 'c-soft-del'", String.class),
                "反例归档必须留 NEGATIVE_VERDICT 原因供归因区分");
    }

    @Test
    public void mark_archived_for_missing_id_should_return_false() {
        boolean archived = repo.markArchived("missing", NOW, com.example.agentweb.domain.refinery.ArchiveReason.MANUAL);
        assertFalse(archived);
    }

    @Test
    public void mark_archived_for_already_archived_chunk_should_return_false_and_not_overwrite_archived_at() {
        Instant firstArchive = NOW.minusSeconds(3600);
        repo.save(newMinimalChunk("c-already").archivedAt(firstArchive).build());

        boolean archived = repo.markArchived("c-already", NOW, com.example.agentweb.domain.refinery.ArchiveReason.MANUAL);

        assertFalse(archived);
        assertEquals(firstArchive,
                repo.findById("c-already").orElseThrow(AssertionError::new).getArchivedAt());
    }

    @Test
    public void archive_expired_before_should_batch_soft_delete_expired_chunks_and_keep_active() {
        repo.save(newMinimalChunk("expired-1").expiresAt(NOW.minusSeconds(3600)).build());
        repo.save(newMinimalChunk("expired-2").expiresAt(NOW.minusSeconds(60)).build());
        repo.save(newMinimalChunk("active").expiresAt(NOW.plusSeconds(3600)).build());
        repo.save(newMinimalChunk("forever").build());
        Instant priorArchive = NOW.minusSeconds(7200);
        repo.save(newMinimalChunk("already-archived")
                .expiresAt(NOW.minusSeconds(3600))
                .archivedAt(priorArchive).build());

        int archived = repo.archiveExpiredBefore(NOW);

        assertEquals(2, archived);
        assertEquals(NOW, repo.findById("expired-1").orElseThrow(AssertionError::new).getArchivedAt());
        assertEquals(NOW, repo.findById("expired-2").orElseThrow(AssertionError::new).getArchivedAt());
        assertNull(repo.findById("active").orElseThrow(AssertionError::new).getArchivedAt());
        assertNull(repo.findById("forever").orElseThrow(AssertionError::new).getArchivedAt());
        assertEquals(priorArchive,
                repo.findById("already-archived").orElseThrow(AssertionError::new).getArchivedAt());
        assertEquals("TTL_EXPIRED", jdbc.queryForObject(
                "SELECT archive_reason FROM chat_rag_chunk WHERE archived_at IS NOT NULL LIMIT 1", String.class),
                "TTL 过期归档必须留 TTL_EXPIRED 原因");
    }

    @Test
    public void delete_by_source_session_id_should_hard_delete_all_chunks_including_archived_without_affecting_other_sessions() {
        repo.save(newMinimalChunk("a-1").sourceSessionId("sess-A").build());
        repo.save(newMinimalChunk("a-2").sourceSessionId("sess-A")
                .archivedAt(NOW.minusSeconds(60)).build());
        repo.save(newMinimalChunk("b-1").sourceSessionId("sess-B").build());

        int deleted = repo.deleteBySourceSessionId("sess-A");

        assertEquals(2, deleted);
        assertFalse(repo.findById("a-1").isPresent());
        assertFalse(repo.findById("a-2").isPresent());
        assertTrue(repo.findById("b-1").isPresent());
    }

    @Test
    public void delete_by_source_session_id_no_match_should_return_zero() {
        repo.save(newMinimalChunk("b-1").sourceSessionId("sess-B").build());
        assertEquals(0, repo.deleteBySourceSessionId("sess-none"));
        assertTrue(repo.findById("b-1").isPresent());
    }

    @Test
    public void save_with_explicit_source_type_tier_env_should_round_trip() {
        RagChunk chunk = newMinimalChunk("c-diag")
                .sourceType(SourceType.DIAGNOSE)
                .tier(TrustTier.PENDING)
                .env("prod")
                .build();
        repo.save(chunk);

        RagChunk loaded = repo.findById("c-diag").orElseThrow(AssertionError::new);
        assertEquals(SourceType.DIAGNOSE, loaded.getSourceType());
        assertEquals(TrustTier.PENDING, loaded.getTier());
        assertEquals("prod", loaded.getEnv());
    }

    @Test
    public void save_without_source_type_tier_env_should_default_to_chat_exploratory_unknown() {
        repo.save(newMinimalChunk("c-default").build());

        RagChunk loaded = repo.findById("c-default").orElseThrow(AssertionError::new);
        assertEquals(SourceType.CHAT, loaded.getSourceType());
        assertEquals(TrustTier.EXPLORATORY, loaded.getTier());
        assertEquals("unknown", loaded.getEnv());
    }

    @Test
    public void update_tier_should_rewrite_tier_column_and_return_true_on_match() {
        repo.save(newMinimalChunk("c-up").tier(TrustTier.PENDING).build());

        boolean updated = repo.updateTier("c-up", TrustTier.VERIFIED);

        assertTrue(updated);
        assertEquals(TrustTier.VERIFIED,
                repo.findById("c-up").orElseThrow(AssertionError::new).getTier());
    }

    @Test
    public void update_tier_for_missing_chunk_should_return_false() {
        assertFalse(repo.updateTier("missing", TrustTier.VERIFIED));
    }

    @Test
    public void delete_by_id_should_hard_delete_target_only() {
        repo.save(newMinimalChunk("d-1").build());
        repo.save(newMinimalChunk("d-2").build());

        boolean deleted = repo.deleteById("d-1");

        assertTrue(deleted);
        assertFalse(repo.findById("d-1").isPresent());
        assertTrue(repo.findById("d-2").isPresent());
    }

    @Test
    public void delete_by_id_for_missing_id_should_return_false() {
        assertFalse(repo.deleteById("missing"));
    }

    @Test
    public void save_3072_dim_embedding_blob_should_round_trip_losslessly() {
        float[] big = new float[3072];
        for (int i = 0; i < big.length; i++) {
            big[i] = (float) (Math.sin(i) * 0.5);
        }
        repo.save(newMinimalChunk("c-3072").embedding(big).build());

        float[] loaded = repo.findById("c-3072")
                .orElseThrow(AssertionError::new)
                .getEmbedding();
        assertArrayEquals(big, loaded, 0f);
    }

    @Test
    public void count_active_by_source_type_should_group_correctly() {
        repo.save(newMinimalChunk("chat-1").sourceType(SourceType.CHAT).build());
        repo.save(newMinimalChunk("chat-2").sourceType(SourceType.CHAT).build());
        repo.save(newMinimalChunk("diag-1").sourceType(SourceType.DIAGNOSE).build());
        repo.save(newMinimalChunk("archived").sourceType(SourceType.CHAT).archivedAt(NOW).build());

        java.util.Map<SourceType, Integer> result = repo.countActiveBySourceType();

        assertEquals(2, result.getOrDefault(SourceType.CHAT, 0));
        assertEquals(1, result.getOrDefault(SourceType.DIAGNOSE, 0));
    }

    @Test
    public void count_active_by_tier_should_group_correctly() {
        repo.save(newMinimalChunk("exp-1").tier(TrustTier.EXPLORATORY).build());
        repo.save(newMinimalChunk("pend-1").tier(TrustTier.PENDING).build());
        repo.save(newMinimalChunk("ver-1").tier(TrustTier.VERIFIED).build());
        repo.save(newMinimalChunk("ver-2").tier(TrustTier.VERIFIED).build());
        repo.save(newMinimalChunk("archived").tier(TrustTier.VERIFIED).archivedAt(NOW).build());

        java.util.Map<TrustTier, Integer> result = repo.countActiveByTier();

        assertEquals(1, result.getOrDefault(TrustTier.EXPLORATORY, 0));
        assertEquals(1, result.getOrDefault(TrustTier.PENDING, 0));
        assertEquals(2, result.getOrDefault(TrustTier.VERIFIED, 0));
    }

    @Test
    public void count_archived_should_return_total_archived_count() {
        repo.save(newMinimalChunk("active").build());
        repo.save(newMinimalChunk("a1").archivedAt(NOW).build());
        repo.save(newMinimalChunk("a2").archivedAt(NOW.minusSeconds(60)).build());

        assertEquals(2, repo.countArchived());
    }

    private RagChunk.Builder newMinimalChunk(String id) {
        return RagChunk.builder()
                .id(id)
                .sourceSessionId("sess-base")
                .agentType(AgentType.CLAUDE)
                .content(new RefinedContent("t", null, "c", "p", "co"))
                .score(0.7)
                .ttlCategory(TtlCategory.GENERAL)
                .createdAt(NOW)
                .embeddingModel("doubao-embedding-vision")
                .embedding(new float[]{0.1f, 0.2f, 0.3f});
    }
}
