package com.example.agentweb.domain.refinery;

import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public class RagChunkTest {

    private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");

    @Test
    public void builder_with_minimal_required_fields_should_construct_successfully() {
        RagChunk chunk = newChunkBuilder().build();
        assertEquals("c-1", chunk.getId());
        assertEquals("sess-1", chunk.getSourceSessionId());
        assertEquals(AgentType.CLAUDE, chunk.getAgentType());
        assertEquals(0.8, chunk.getScore(), 1e-6);
        assertEquals(TtlCategory.CODE, chunk.getTtlCategory());
        assertEquals("doubao-embedding-vision", chunk.getEmbeddingModel());
        assertNull(chunk.getArchivedAt());
        assertNull(chunk.getExpiresAt());
        assertNull(chunk.getSourceMsgRange());
    }

    @Test
    public void score_out_of_0_to_1_range_should_throw() {
        assertThrows(IllegalArgumentException.class,
                () -> newChunkBuilder().score(-0.01).build());
        assertThrows(IllegalArgumentException.class,
                () -> newChunkBuilder().score(1.01).build());
        assertThrows(IllegalArgumentException.class,
                () -> newChunkBuilder().score(Double.NaN).build());
    }

    @Test
    public void embedding_null_or_empty_should_throw() {
        assertThrows(NullPointerException.class,
                () -> newChunkBuilder().embedding(null).build());
        assertThrows(IllegalArgumentException.class,
                () -> newChunkBuilder().embedding(new float[0]).build());
    }

    @Test
    public void missing_required_fields_should_throw_null_pointer_exception() {
        assertThrows(NullPointerException.class,
                () -> newChunkBuilder().id(null).build());
        assertThrows(NullPointerException.class,
                () -> newChunkBuilder().content(null).build());
        assertThrows(NullPointerException.class,
                () -> newChunkBuilder().agentType(null).build());
        assertThrows(NullPointerException.class,
                () -> newChunkBuilder().ttlCategory(null).build());
        assertThrows(NullPointerException.class,
                () -> newChunkBuilder().embeddingModel(null).build());
    }

    @Test
    public void archive_first_call_should_set_archived_at() {
        RagChunk chunk = newChunkBuilder().build();
        Instant when = NOW.plusSeconds(3600);
        chunk.archive(when);
        assertEquals(when, chunk.getArchivedAt());
    }

    @Test
    public void archive_called_twice_should_throw_illegal_state_exception() {
        RagChunk chunk = newChunkBuilder().build();
        chunk.archive(NOW);
        assertThrows(IllegalStateException.class, () -> chunk.archive(NOW.plusSeconds(1)));
    }

    @Test
    public void archive_when_null_should_throw_null_pointer_exception() {
        RagChunk chunk = newChunkBuilder().build();
        assertThrows(NullPointerException.class, () -> chunk.archive(null));
    }

    @Test
    public void builder_can_set_optional_fields() {
        Instant expiresAt = NOW.plusSeconds(86400);
        Instant archivedAt = NOW.plusSeconds(3600);
        RagChunk chunk = newChunkBuilder()
                .sourceMsgRange("msg_5..msg_12")
                .expiresAt(expiresAt)
                .archivedAt(archivedAt)
                .build();
        assertEquals("msg_5..msg_12", chunk.getSourceMsgRange());
        assertEquals(expiresAt, chunk.getExpiresAt());
        assertEquals(archivedAt, chunk.getArchivedAt());
    }

    @Test
    public void builder_without_source_fields_should_default_to_chat_exploratory_unknown() {
        RagChunk chunk = newChunkBuilder().build();
        assertEquals(SourceType.CHAT, chunk.getSourceType());
        assertEquals(TrustTier.EXPLORATORY, chunk.getTier());
        assertEquals("unknown", chunk.getEnv());
    }

    @Test
    public void builder_can_explicitly_set_source_type_tier_env() {
        RagChunk chunk = newChunkBuilder()
                .sourceType(SourceType.DIAGNOSE)
                .tier(TrustTier.PENDING)
                .env("prod")
                .build();
        assertEquals(SourceType.DIAGNOSE, chunk.getSourceType());
        assertEquals(TrustTier.PENDING, chunk.getTier());
        assertEquals("prod", chunk.getEnv());
    }

    @Test
    public void upgrade_tier_from_pending_to_verified_should_succeed() {
        RagChunk chunk = newChunkBuilder().tier(TrustTier.PENDING).build();
        chunk.upgradeTier(TrustTier.VERIFIED);
        assertEquals(TrustTier.VERIFIED, chunk.getTier());
    }

    @Test
    public void upgrade_tier_downgrading_toward_exploratory_should_throw() {
        RagChunk chunk = newChunkBuilder().tier(TrustTier.VERIFIED).build();
        assertThrows(IllegalArgumentException.class,
                () -> chunk.upgradeTier(TrustTier.PENDING));
        assertThrows(IllegalArgumentException.class,
                () -> chunk.upgradeTier(TrustTier.EXPLORATORY));
    }

    @Test
    public void upgrade_tier_null_should_throw() {
        RagChunk chunk = newChunkBuilder().build();
        assertThrows(NullPointerException.class, () -> chunk.upgradeTier(null));
    }

    private RagChunk.Builder newChunkBuilder() {
        RefinedContent content = new RefinedContent("title", null, "ctx", "proc", "concl");
        return RagChunk.builder()
                .id("c-1")
                .sourceSessionId("sess-1")
                .agentType(AgentType.CLAUDE)
                .content(content)
                .score(0.8)
                .ttlCategory(TtlCategory.CODE)
                .createdAt(NOW)
                .embeddingModel("doubao-embedding-vision")
                .embedding(new float[]{0.1f, 0.2f, 0.3f});
    }
}
