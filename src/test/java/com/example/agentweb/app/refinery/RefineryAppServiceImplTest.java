package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.refinery.DiscardedRefineRecord;
import com.example.agentweb.domain.refinery.DiscardedRefineRepository;
import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.SessionRefineryState;
import com.example.agentweb.domain.refinery.SessionRefineryStateRepository;
import com.example.agentweb.domain.refinery.ConversationTurn;
import com.example.agentweb.domain.refinery.ConversationView;
import com.example.agentweb.domain.refinery.DefaultTrustTierPolicy;
import com.example.agentweb.domain.refinery.EmbeddingClient;
import com.example.agentweb.domain.refinery.RefinedContent;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.domain.refinery.TrustTierPolicy;
import com.example.agentweb.domain.refinery.TtlCategory;
import com.example.agentweb.domain.refinery.VerdictSignal;
import com.example.agentweb.config.refinery.RefineryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RefineryAppServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");

    @Mock private ChatViewBuilder viewBuilder;
    @Mock private ConversationRefinery refinery;
    @Mock private EmbeddingClient embeddingClient;
    @Mock private RagChunkRepository chunkRepo;
    @Mock private SessionRefineryStateRepository stateRepo;
    @Mock private DiscardedRefineRepository discardedRepo;

    private RefineryProperties props;
    private TrustTierPolicy tierPolicy;
    private RefineryAppServiceImpl service;

    @BeforeEach
    public void setUp() {
        props = new RefineryProperties();
        props.getRefine().setScoreThreshold(0.5);
        props.getTtl().setDeployDays(30);
        props.getTtl().setCodeDays(14);
        props.getTtl().setBusinessDays(60);
        props.getTtl().setGeneralDays(30);
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(embeddingClient.modelName()).thenReturn("doubao-embedding-vision");
        when(embeddingClient.dimension()).thenReturn(3);
        tierPolicy = new DefaultTrustTierPolicy();
        service = new RefineryAppServiceImpl(
                viewBuilder, refinery, tierPolicy, embeddingClient,
                chunkRepo, stateRepo, discardedRepo, props, fixedClock);
    }

    @Test
    public void refineAndIngest_happy_should_save_chunk_and_state() {
        ConversationView view = newView("sess-1");
        when(viewBuilder.build("sess-1")).thenReturn(Optional.of(view));
        when(refinery.refine(view)).thenReturn(highScoreResult());
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        boolean saved = service.refineAndIngest("sess-1");

        assertTrue(saved);
        ArgumentCaptor<RagChunk> chunkCap = ArgumentCaptor.forClass(RagChunk.class);
        verify(chunkRepo).save(chunkCap.capture());
        RagChunk chunk = chunkCap.getValue();
        assertEquals("sess-1", chunk.getSourceSessionId());
        assertEquals(TtlCategory.DEPLOY, chunk.getTtlCategory());
        assertEquals(0.87, chunk.getScore(), 1e-6);
        assertEquals(NOW, chunk.getCreatedAt());
        assertEquals(NOW.plusSeconds(30L * 86400), chunk.getExpiresAt());
        assertEquals("doubao-embedding-vision", chunk.getEmbeddingModel());
        assertEquals(3, chunk.getEmbedding().length);

        ArgumentCaptor<SessionRefineryState> stateCap = ArgumentCaptor.forClass(SessionRefineryState.class);
        verify(stateRepo).save(stateCap.capture());
        SessionRefineryState state = stateCap.getValue();
        assertEquals("sess-1", state.getSessionId());
        assertEquals(chunk.getId(), state.getLastChunkId());
        assertNull(state.getLastError());
        // Phase 1.3: chat source 自动落到 EXPLORATORY tier
        assertEquals(SourceType.CHAT, chunk.getSourceType());
        assertEquals(TrustTier.EXPLORATORY, chunk.getTier());
        assertEquals("unknown", chunk.getEnv());
    }

    @Test
    public void refineAndIngest_diagnose_positive_view_lands_in_VERIFIED_pool() {
        ConversationView view = ConversationView.builder()
                .sourceId("diag-1")
                .sourceType(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir("/w")
                .env("prod")
                .verdict(VerdictSignal.POSITIVE)
                .turns(Arrays.asList(
                        new ConversationTurn("user", "trace exception", NOW.minusSeconds(60)),
                        new ConversationTurn("assistant", "root cause: X", NOW.minusSeconds(30))))
                .build();
        when(viewBuilder.build("diag-1")).thenReturn(Optional.of(view));
        when(refinery.refine(view)).thenReturn(highScoreResult());
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        assertTrue(service.refineAndIngest("diag-1"));

        ArgumentCaptor<RagChunk> chunkCap = ArgumentCaptor.forClass(RagChunk.class);
        verify(chunkRepo).save(chunkCap.capture());
        RagChunk chunk = chunkCap.getValue();
        assertEquals(SourceType.DIAGNOSE, chunk.getSourceType());
        assertEquals(TrustTier.VERIFIED, chunk.getTier());
        assertEquals("prod", chunk.getEnv());
    }

    @Test
    public void refineAndIngest_diagnose_none_view_lands_in_PENDING_pool() {
        ConversationView view = ConversationView.builder()
                .sourceId("diag-2")
                .sourceType(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir("/w")
                .env("prod")
                .verdict(VerdictSignal.NONE)
                .turns(Arrays.asList(
                        new ConversationTurn("user", "issue", NOW.minusSeconds(60))))
                .build();
        when(viewBuilder.build("diag-2")).thenReturn(Optional.of(view));
        when(refinery.refine(view)).thenReturn(highScoreResult());
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        service.refineAndIngest("diag-2");

        ArgumentCaptor<RagChunk> chunkCap = ArgumentCaptor.forClass(RagChunk.class);
        verify(chunkRepo).save(chunkCap.capture());
        assertEquals(TrustTier.PENDING, chunkCap.getValue().getTier());
    }

    @Test
    public void refineAndIngest_diagnose_negative_view_blocked_by_policy_no_refine_no_state() {
        ConversationView view = ConversationView.builder()
                .sourceId("diag-3")
                .sourceType(SourceType.DIAGNOSE)
                .agentType(AgentType.CLAUDE)
                .workingDir("/w")
                .env("prod")
                .verdict(VerdictSignal.NEGATIVE)
                .turns(Arrays.asList(
                        new ConversationTurn("user", "bad", NOW.minusSeconds(60))))
                .build();
        when(viewBuilder.build("diag-3")).thenReturn(Optional.of(view));

        assertFalse(service.refineAndIngest("diag-3"));
        verify(refinery, never()).refine(any());
        verify(chunkRepo, never()).save(any());
        verify(stateRepo, never()).save(any());
    }

    @Test
    public void refineAndIngest_score_below_threshold_does_not_save_chunk_state_has_no_chunkId() {
        ConversationView view = newView("sess-low");
        when(viewBuilder.build("sess-low")).thenReturn(Optional.of(view));
        when(refinery.refine(view)).thenReturn(lowScoreResult());

        boolean saved = service.refineAndIngest("sess-low");

        assertFalse(saved);
        verify(chunkRepo, never()).save(any());
        verify(embeddingClient, never()).embed(any());

        ArgumentCaptor<SessionRefineryState> stateCap = ArgumentCaptor.forClass(SessionRefineryState.class);
        verify(stateRepo).save(stateCap.capture());
        SessionRefineryState state = stateCap.getValue();
        assertNull(state.getLastChunkId());
        assertNotNull(state.getLastError());
        assertTrue(state.getLastError().contains("below threshold"));
    }

    @Test
    public void refineAndIngest_belowThreshold_persistDiscarded_on_should_save_discarded_record() {
        ConversationView view = newView("sess-low");
        when(viewBuilder.build("sess-low")).thenReturn(Optional.of(view));
        when(refinery.refine(view)).thenReturn(lowScoreResult());

        boolean saved = service.refineAndIngest("sess-low");

        assertFalse(saved);
        // 仍走 below-threshold 既有路径: 不建 chunk, state 记哨兵
        verify(chunkRepo, never()).save(any());
        // 旁路落库 discarded, 字段来自 view + result
        ArgumentCaptor<DiscardedRefineRecord> cap = ArgumentCaptor.forClass(DiscardedRefineRecord.class);
        verify(discardedRepo).save(cap.capture());
        DiscardedRefineRecord rec = cap.getValue();
        assertEquals("sess-low", rec.getSourceSessionId());
        assertEquals(SourceType.CHAT, rec.getSourceType());
        assertEquals("闲聊", rec.getTitle());
        assertEquals(0.2, rec.getScore(), 1e-9);
        assertEquals(0.5, rec.getThreshold(), 1e-9);
        assertEquals(TtlCategory.GENERAL, rec.getTtlCategory());
        assertEquals("CLAUDE", rec.getAgentType());
        assertEquals(NOW, rec.getCreatedAt());
        assertEquals(DiscardedRefineRecord.REASON_BELOW_THRESHOLD, rec.getReason());
    }

    @Test
    public void refineAndIngest_belowThreshold_persistDiscarded_off_should_not_save_discarded() {
        props.getRefine().setPersistDiscarded(false);
        ConversationView view = newView("sess-low");
        when(viewBuilder.build("sess-low")).thenReturn(Optional.of(view));
        when(refinery.refine(view)).thenReturn(lowScoreResult());

        assertFalse(service.refineAndIngest("sess-low"));
        verify(discardedRepo, never()).save(any());
        // below-threshold 主流程不受影响: state 仍记哨兵
        assertEquals(SessionRefineryState.LAST_ERROR_BELOW_THRESHOLD, capturedState().getLastError());
    }

    @Test
    public void refineAndIngest_belowThreshold_discarded_save_throws_should_not_break_main_flow() {
        ConversationView view = newView("sess-low");
        when(viewBuilder.build("sess-low")).thenReturn(Optional.of(view));
        when(refinery.refine(view)).thenReturn(lowScoreResult());
        org.mockito.Mockito.doThrow(new RuntimeException("disk full"))
                .when(discardedRepo).save(any());

        // 旁路落库失败被吞, below-threshold 主流程照常返回 false 并写哨兵 state
        boolean saved = service.refineAndIngest("sess-low");

        assertFalse(saved);
        ArgumentCaptor<SessionRefineryState> stateCap = ArgumentCaptor.forClass(SessionRefineryState.class);
        verify(stateRepo).save(stateCap.capture());
        assertEquals(SessionRefineryState.LAST_ERROR_BELOW_THRESHOLD, stateCap.getValue().getLastError());
    }

    @Test
    public void refineAndIngest_refine_throws_state_records_last_error_no_chunk_saved() {
        ConversationView view = newView("sess-err");
        when(viewBuilder.build("sess-err")).thenReturn(Optional.of(view));
        when(refinery.refine(view))
                .thenThrow(new RefineException("LLM blew up"));

        boolean saved = service.refineAndIngest("sess-err");

        assertFalse(saved);
        verify(chunkRepo, never()).save(any());
        ArgumentCaptor<SessionRefineryState> stateCap = ArgumentCaptor.forClass(SessionRefineryState.class);
        verify(stateRepo).save(stateCap.capture());
        assertTrue(stateCap.getValue().getLastError().contains("LLM blew up"));
    }

    @Test
    public void refineAndIngest_embed_throws_state_records_last_error_no_chunk_saved() {
        ConversationView view = newView("sess-embed-err");
        when(viewBuilder.build("sess-embed-err")).thenReturn(Optional.of(view));
        when(refinery.refine(view)).thenReturn(highScoreResult());
        when(embeddingClient.embed(any())).thenThrow(new RuntimeException("HTTP 503"));

        boolean saved = service.refineAndIngest("sess-embed-err");

        assertFalse(saved);
        verify(chunkRepo, never()).save(any());
        ArgumentCaptor<SessionRefineryState> stateCap = ArgumentCaptor.forClass(SessionRefineryState.class);
        verify(stateRepo).save(stateCap.capture());
        assertTrue(stateCap.getValue().getLastError().contains("HTTP 503"));
    }

    @Test
    public void refineAndIngest_state_exists_and_lastMessageAtSeen_unchanged_should_skip_rescoring() {
        ConversationView view = newView("sess-dup");
        when(viewBuilder.build("sess-dup")).thenReturn(Optional.of(view));
        Instant currentLastMsg = view.getTurns().get(view.getTurns().size() - 1).getTimestamp();
        SessionRefineryState existing = new SessionRefineryState(
                "sess-dup", NOW.minusSeconds(60), currentLastMsg, "c-prev", null, 0);
        when(stateRepo.findBySessionId("sess-dup"))
                .thenReturn(java.util.Optional.of(existing));

        boolean saved = service.refineAndIngest("sess-dup");

        assertFalse(saved);
        verify(refinery, never()).refine(any());
        verify(embeddingClient, never()).embed(any());
        verify(chunkRepo, never()).save(any());
        verify(stateRepo, never()).save(any());
    }

    @Test
    public void refineAndIngest_session_not_found_returns_false_no_state_written() {
        when(viewBuilder.build("missing")).thenReturn(Optional.empty());

        boolean saved = service.refineAndIngest("missing");

        assertFalse(saved);
        verify(refinery, never()).refine(any());
        verify(stateRepo, never()).save(any());
    }

    @Test
    public void refineAndIngest_first_failure_retryCount_becomes_1() {
        ConversationView view = newView("sess-err");
        when(viewBuilder.build("sess-err")).thenReturn(Optional.of(view));
        when(stateRepo.findBySessionId("sess-err")).thenReturn(java.util.Optional.empty());
        when(refinery.refine(view)).thenThrow(new RefineException("boom"));

        service.refineAndIngest("sess-err");

        assertEquals(1, capturedState().getRetryCount(), "首次失败 retryCount 0→1");
    }

    @Test
    public void refineAndIngest_after_2_failures_another_failure_retryCount_accumulates_to_3() {
        ConversationView view = newView("sess-err");
        when(viewBuilder.build("sess-err")).thenReturn(Optional.of(view));
        // 既有 state: 已失败 2 次, 且消息时间已变化 (有新消息触发本轮重评)
        when(stateRepo.findBySessionId("sess-err")).thenReturn(java.util.Optional.of(
                new SessionRefineryState("sess-err", NOW.minusSeconds(120),
                        NOW.minusSeconds(9999), null, "boom", 2)));
        when(refinery.refine(view)).thenThrow(new RefineException("boom again"));

        service.refineAndIngest("sess-err");

        assertEquals(3, capturedState().getRetryCount(), "失败应在既有 retryCount 上累加");
    }

    @Test
    public void refineAndIngest_on_success_retryCount_resets_to_zero() {
        ConversationView view = newView("sess-ok");
        when(viewBuilder.build("sess-ok")).thenReturn(Optional.of(view));
        when(stateRepo.findBySessionId("sess-ok")).thenReturn(java.util.Optional.of(
                new SessionRefineryState("sess-ok", NOW.minusSeconds(120),
                        NOW.minusSeconds(9999), null, "boom", 2)));
        when(refinery.refine(view)).thenReturn(highScoreResult());
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        assertTrue(service.refineAndIngest("sess-ok"));
        assertEquals(0, capturedState().getRetryCount(), "成功应把 retryCount 归零");
    }

    @Test
    public void refineAndIngest_belowThreshold_retryCount_resets_and_records_sentinel() {
        ConversationView view = newView("sess-bt");
        when(viewBuilder.build("sess-bt")).thenReturn(Optional.of(view));
        when(stateRepo.findBySessionId("sess-bt")).thenReturn(java.util.Optional.of(
                new SessionRefineryState("sess-bt", NOW.minusSeconds(120),
                        NOW.minusSeconds(9999), null, "boom", 2)));
        when(refinery.refine(view)).thenReturn(lowScoreResult());

        assertFalse(service.refineAndIngest("sess-bt"));
        SessionRefineryState s = capturedState();
        assertEquals(0, s.getRetryCount(), "below-threshold 不重试, retryCount 归零");
        assertEquals(SessionRefineryState.LAST_ERROR_BELOW_THRESHOLD, s.getLastError());
    }

    @Test
    public void refineAndIngest_truly_failed_session_shouldSkip_allows_rescoring_and_accumulates_retryCount() {
        // 真·失败 (last_error 非 null 且非 below-threshold 哨兵) 的会话即使被再次拉取也应放行重评,
        // 与"成功/below-threshold 且无新消息则跳过"区分. retryCount 在既有值上累加.
        ConversationView view = newView("sess-retry");
        when(viewBuilder.build("sess-retry")).thenReturn(Optional.of(view));
        Instant lastMsg = view.getTurns().get(view.getTurns().size() - 1).getTimestamp();
        when(stateRepo.findBySessionId("sess-retry")).thenReturn(java.util.Optional.of(
                new SessionRefineryState("sess-retry", NOW.minusSeconds(60), lastMsg, null, "boom", 1)));
        when(refinery.refine(view)).thenThrow(new RefineException("boom again"));

        service.refineAndIngest("sess-retry");

        verify(refinery).refine(view);
        assertEquals(2, capturedState().getRetryCount());
    }

    @Test
    public void refineAndIngest_succeeded_session_with_no_new_message_shouldSkip_should_skip() {
        // 对照组: 成功 (last_error=null) 且 last_message_at_seen 与当前一致 → 跳过, 不重评.
        ConversationView view = newView("sess-done");
        when(viewBuilder.build("sess-done")).thenReturn(Optional.of(view));
        Instant lastMsg = view.getTurns().get(view.getTurns().size() - 1).getTimestamp();
        when(stateRepo.findBySessionId("sess-done")).thenReturn(java.util.Optional.of(
                new SessionRefineryState("sess-done", NOW.minusSeconds(60), lastMsg, "c-prev", null, 0)));

        assertFalse(service.refineAndIngest("sess-done"));
        verify(refinery, never()).refine(any());
        verify(stateRepo, never()).save(any());
    }

    private SessionRefineryState capturedState() {
        ArgumentCaptor<SessionRefineryState> captor = ArgumentCaptor.forClass(SessionRefineryState.class);
        verify(stateRepo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    public void refineAndIngest_TtlCategory_CODE_uses_code_days() {
        ConversationView view = newView("sess-code");
        when(viewBuilder.build("sess-code")).thenReturn(Optional.of(view));
        RefineResult codeResult = new RefineResult(
                0.8, TtlCategory.CODE,
                new RefinedContent("t", Arrays.asList("s"), "c", "p", "co"));
        when(refinery.refine(view)).thenReturn(codeResult);
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        service.refineAndIngest("sess-code");

        ArgumentCaptor<RagChunk> chunkCap = ArgumentCaptor.forClass(RagChunk.class);
        verify(chunkRepo).save(chunkCap.capture());
        assertEquals(NOW.plusSeconds(14L * 86400), chunkCap.getValue().getExpiresAt());
    }

    private ConversationView newView(String id) {
        List<ConversationTurn> turns = new ArrayList<>();
        turns.add(new ConversationTurn("user", "msg1", NOW.minusSeconds(7100)));
        turns.add(new ConversationTurn("assistant", "msg2", NOW.minusSeconds(7000)));
        return ConversationView.builder()
                .sourceId(id)
                .sourceType(SourceType.CHAT)
                .agentType(AgentType.CLAUDE)
                .workingDir("/w")
                .turns(turns)
                .build();
    }

    private RefineResult highScoreResult() {
        return new RefineResult(
                0.87, TtlCategory.DEPLOY,
                new RefinedContent("SkipList 配置遗漏",
                        Arrays.asList("启动卡住", "deploy 失败"),
                        "ctx", "1) 2) 3)", "补齐 SkipList"));
    }

    private RefineResult lowScoreResult() {
        return new RefineResult(
                0.2, TtlCategory.GENERAL,
                new RefinedContent("闲聊", Arrays.asList(), "", "", ""));
    }

    @Test
    public void reembedActive_should_refresh_up_to_limit_with_description_in_embed_text() {
        RagChunk first = reembedChunk("c-1", "下单一直转圈时");
        RagChunk second = reembedChunk("c-2", "");
        RagChunk beyondLimit = reembedChunk("c-3", "x");
        when(chunkRepo.findActive(any())).thenReturn(Arrays.asList(first, second, beyondLimit));
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(chunkRepo.updateEmbedding(any(), any(), any())).thenReturn(true);

        int refreshed = service.reembedActive(2);

        org.junit.jupiter.api.Assertions.assertEquals(2, refreshed);
        org.mockito.ArgumentCaptor<String> textCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(embeddingClient, org.mockito.Mockito.times(2))
                .embed(textCaptor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                textCaptor.getAllValues().get(0).contains("下单一直转圈时"),
                "triggerDescription 应参与重嵌入文本");
        org.mockito.Mockito.verify(chunkRepo, org.mockito.Mockito.never())
                .updateEmbedding(org.mockito.ArgumentMatchers.eq("c-3"), any(), any());
    }

    @Test
    public void reembedActive_should_skip_failed_chunk_and_continue() {
        RagChunk broken = reembedChunk("c-bad", "a");
        RagChunk healthy = reembedChunk("c-ok", "b");
        when(chunkRepo.findActive(any())).thenReturn(Arrays.asList(broken, healthy));
        when(embeddingClient.embed(any()))
                .thenThrow(new IllegalStateException("embed down"))
                .thenReturn(new float[]{0.1f});
        when(chunkRepo.updateEmbedding(any(), any(), any())).thenReturn(true);

        int refreshed = service.reembedActive(10);

        org.junit.jupiter.api.Assertions.assertEquals(1, refreshed);
        org.mockito.Mockito.verify(chunkRepo)
                .updateEmbedding(org.mockito.ArgumentMatchers.eq("c-ok"), any(), any());
    }

    private RagChunk reembedChunk(String id, String triggerDescription) {
        return RagChunk.builder()
                .id(id)
                .sourceSessionId("sess-" + id)
                .agentType(AgentType.CLAUDE)
                .content(new RefinedContent("标题", Arrays.asList("信号"), triggerDescription,
                        "ctx", "p", "结论"))
                .score(0.8)
                .ttlCategory(TtlCategory.GENERAL)
                .createdAt(NOW)
                .embeddingModel("doubao-embedding-vision")
                .embedding(new float[]{0.1f, 0.2f, 0.3f})
                .sourceType(SourceType.CHAT)
                .tier(com.example.agentweb.domain.refinery.TrustTier.EXPLORATORY)
                .env("test")
                .build();
    }
}
