package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.EmbeddingClient;
import com.example.agentweb.domain.refinery.RefinedContent;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.domain.refinery.TtlCategory;
import com.example.agentweb.infra.refinery.config.RefineryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
public class RefineryRecallerTest {

    private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");

    @Mock private EmbeddingClient embeddingClient;
    @Mock private RagChunkRepository chunkRepo;
    @Mock private RecallDetailStore detailStore;
    @Mock private com.example.agentweb.app.agentrun.WorkspaceContextResolver workspaceResolver;

    private RefineryProperties props;
    private RefineryRecaller recaller;

    @BeforeEach
    public void setUp() {
        props = new RefineryProperties();
        props.getRecall().setTopK(2);
        props.getEmbedding().setModel("qwen/qwen3-embedding-8b");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(detailStore.store(any(), org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> {
            List<RagChunk> hits = inv.getArgument(1);
            return new java.util.ArrayList<String>(Collections.nCopies(hits.size(), (String) null));
        });
        recaller = new RefineryRecaller(embeddingClient, chunkRepo, props, clock, detailStore, workspaceResolver);
    }

    @Test
    public void recall_returns_topK_by_cosine_similarity() {
        when(embeddingClient.embed("query")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk near = chunkOf("c-near", new float[]{0.99f, 0.1f, 0f}, "近");
        RagChunk middle = chunkOf("c-mid", new float[]{0.7f, 0.7f, 0f}, "中");
        RagChunk far = chunkOf("c-far", new float[]{0f, 0f, 1f}, "远");
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(far, near, middle));

        List<RagChunk> hits = recaller.recall("query", 2);

        assertEquals(2, hits.size());
        assertEquals("c-near", hits.get(0).getId());
        assertEquals("c-mid", hits.get(1).getId());
    }

    @Test
    public void recall_empty_candidate_pool_returns_empty_list() {
        when(embeddingClient.embed(any())).thenReturn(new float[]{1f, 0f, 0f});
        when(chunkRepo.findActive(NOW)).thenReturn(Collections.emptyList());

        List<RagChunk> hits = recaller.recall("anything", 3);

        assertTrue(hits.isEmpty());
    }

    @Test
    public void recall_topK_greater_than_pool_returns_pool_size() {
        when(embeddingClient.embed(any())).thenReturn(new float[]{1f, 0f, 0f});
        when(chunkRepo.findActive(NOW)).thenReturn(Collections.singletonList(
                chunkOf("only", new float[]{0.5f, 0.5f, 0f}, "x")));

        List<RagChunk> hits = recaller.recall("q", 10);

        assertEquals(1, hits.size());
    }

    @Test
    public void recall_signal_overlap_boosts_ranking_when_beta_weight_is_1() {
        props.getRecall().getRanking().setVectorWeight(0d);
        props.getRecall().getRanking().setSignalWeight(1d);
        props.getRecall().getRanking().setTimeDecayWeight(0d);
        when(embeddingClient.embed("退款 失败")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk withSignal = chunkOf("c-signal",
                new float[]{0f, 1f, 0f},
                "退款流程", Arrays.asList("退款", "失败"));
        RagChunk vectorOnly = chunkOf("c-vector",
                new float[]{0.99f, 0.1f, 0f},
                "其它", Arrays.asList("无关词"));
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(vectorOnly, withSignal));

        List<RagChunk> hits = recaller.recall("退款 失败", 2);

        assertEquals("c-signal", hits.get(0).getId());
    }

    @Test
    public void recall_time_decay_old_chunk_ranks_lower_when_gamma_is_high() {
        props.getRecall().getRanking().setVectorWeight(0d);
        props.getRecall().getRanking().setSignalWeight(0d);
        props.getRecall().getRanking().setTimeDecayWeight(1d);
        props.getRecall().setHalfLifeDays(10d);
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk fresh = RagChunk.builder()
                .id("fresh").sourceSessionId("s").agentType(AgentType.CLAUDE)
                .content(new RefinedContent("t", Arrays.asList(), "", "", ""))
                .score(0.9).ttlCategory(TtlCategory.GENERAL)
                .createdAt(NOW.minusSeconds(86400))
                .embeddingModel("m").embedding(new float[]{0.5f, 0.5f, 0f}).build();
        RagChunk old = RagChunk.builder()
                .id("old").sourceSessionId("s").agentType(AgentType.CLAUDE)
                .content(new RefinedContent("t", Arrays.asList(), "", "", ""))
                .score(0.9).ttlCategory(TtlCategory.GENERAL)
                .createdAt(NOW.minusSeconds(86400 * 100))
                .embeddingModel("m").embedding(new float[]{0.5f, 0.5f, 0f}).build();
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(old, fresh));

        List<RagChunk> hits = recaller.recall("q", 2);

        assertEquals("fresh", hits.get(0).getId());
        assertEquals("old", hits.get(1).getId());
    }

    @Test
    public void traceForChat_hit_uses_full_message_as_query_and_prepends_reference_context() {
        when(embeddingClient.embed("退款问题")).thenReturn(new float[]{1f, 0f, 0f});
        when(chunkRepo.findActive(NOW)).thenReturn(Collections.singletonList(
                chunkOf("c-1", new float[]{0.9f, 0.1f, 0f}, "退款 502")));

        RecallOutcome outcome = recaller.traceForChat("退款问题", null).toOutcome();

        assertTrue(outcome.isRecalled());
        assertEquals("退款问题", outcome.getQuery());
        assertTrue(outcome.getMessage().contains("退款问题"), "整条消息作 query 应保留在末尾");
        assertTrue(outcome.getMessage().contains("退款 502"), "命中标题应出现");
        assertTrue(outcome.getMessage().contains("[历史参考"), "应有参考块标记");
        assertEquals(1, outcome.getHits().size());
        RecallHit hit = outcome.getHits().get(0);
        assertEquals("退款 502", hit.getTitle());
        assertEquals("conclusion-c-1", hit.getConclusion());
        assertEquals("sess-x", hit.getSessionId());
    }

    @Test
    public void traceForChat_slash_command_does_not_recall_handed_back_to_expander() {
        RecallOutcome outcome = recaller.traceForChat("/some-skill 参数", null).toOutcome();

        assertFalse(outcome.isRecalled(), "斜杠命令应交回 SlashCommandExpander, 不召回");
        assertEquals("/some-skill 参数", outcome.getMessage());
        verify(embeddingClient, never()).embed(any());
    }

    @Test
    public void traceForChat_blank_message_does_not_recall() {
        RecallOutcome outcome = recaller.traceForChat("   ", null).toOutcome();

        assertFalse(outcome.isRecalled());
        verify(embeddingClient, never()).embed(any());
    }

    @Test
    public void traceForChat_null_message_does_not_recall() {
        RecallOutcome outcome = recaller.traceForChat(null, null).toOutcome();

        assertFalse(outcome.isRecalled());
        verify(embeddingClient, never()).embed(any());
    }

    @Test
    public void traceForChat_no_hits_silently_passes_through_without_placeholder() {
        when(embeddingClient.embed("无关")).thenReturn(new float[]{1f, 0f, 0f});
        when(chunkRepo.findActive(NOW)).thenReturn(Collections.emptyList());

        RecallOutcome outcome = recaller.traceForChat("无关", null).toOutcome();

        // 0 命中: 静默原样透传, 不弹空卡片/不注入"无历史参考"占位
        assertFalse(outcome.isRecalled());
        assertEquals("无关", outcome.getMessage());
        assertFalse(outcome.getMessage().contains("无历史参考"));
    }

    @Test
    public void traceForChat_召回异常_静默降级原样透传() {
        when(embeddingClient.embed(any())).thenThrow(new RuntimeException("ark down"));

        RecallOutcome outcome = recaller.traceForChat("退款问题", null).toOutcome();

        assertFalse(outcome.isRecalled());
        assertEquals("退款问题", outcome.getMessage());
    }

    @Test
    public void traceForChat_slashCommand_returnsSkippedWithoutEmbedding() {
        RecallTrace trace = recaller.traceForChat("/some-skill 参数", null);

        assertEquals(RecallStatus.SKIPPED, trace.getStatus());
        assertEquals("SLASH_COMMAND", trace.getSkipReason());
        assertEquals("/some-skill 参数", trace.getAugmentedMessage());
        verify(embeddingClient, never()).embed(any());
    }

    @Test
    public void traceForChat_noHit_keepsStatsAndTopVectorScore() {
        props.getRecall().setTopK(3);
        props.getRecall().setMinVectorScore(0.5d);
        when(embeddingClient.embed("无关")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk below1 = chunkOf("below1", new float[]{0.3f, 0.954f, 0f}, "弱1");
        RagChunk below2 = chunkOf("below2", new float[]{0.2f, 0.98f, 0f}, "弱2");
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(below1, below2));

        RecallTrace trace = recaller.traceForChat("无关", null);

        assertEquals(RecallStatus.NO_HIT, trace.getStatus());
        assertEquals("无关", trace.getQuery());
        assertEquals("无关", trace.getAugmentedMessage());
        assertTrue(trace.getHits().isEmpty());
        assertEquals(2, trace.getStats().getActiveCount());
        assertEquals(2, trace.getStats().getFilteredCount());
        assertEquals(2, trace.getStats().getBelowVectorFloor());
        assertEquals(0, trace.getStats().getRankedCount());
        assertEquals(0, trace.getStats().getBadVectorCount());
        assertEquals(3, trace.getStats().getTopK());
        assertEquals("qwen/qwen3-embedding-8b", trace.getStats().getEmbeddingModel());
        assertEquals(2048, trace.getStats().getEmbeddingDimension());
        assertEquals(0.3d, trace.getStats().getTopVectorScore(), 1e-2);
        assertEquals(null, trace.getStats().getTopFinalScore());
    }

    @Test
    public void traceForChat_hit_containsScoredHitsAndAugmentedMessage() {
        props.getRecall().getRanking().setVectorWeight(1d);
        props.getRecall().getRanking().setSignalWeight(0d);
        props.getRecall().getRanking().setTimeDecayWeight(0d);
        when(embeddingClient.embed("退款问题")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk hit = chunkOfWithSource("c-1", new float[]{0.9f, 0.1f, 0f},
                "退款 502", SourceType.CHAT, TrustTier.EXPLORATORY);
        when(chunkRepo.findActive(NOW)).thenReturn(Collections.singletonList(hit));

        RecallTrace trace = recaller.traceForChat("退款问题", null);

        assertEquals(RecallStatus.HIT, trace.getStatus());
        assertEquals(1, trace.getHits().size());
        ScoredRecallHit scored = trace.getHits().get(0);
        assertEquals("c-1", scored.getChunkId());
        assertEquals(1, scored.getRankNo());
        assertEquals("退款 502", scored.getTitle());
        assertEquals("sess-x", scored.getSourceSessionId());
        assertEquals("CHAT", scored.getSourceType());
        assertEquals("EXPLORATORY", scored.getTier());
        assertEquals("doubao-embedding-vision", scored.getEmbeddingModel());
        assertEquals(0.8d, scored.getChunkScore(), 1e-9);
        assertEquals(scored.getVectorScore(), scored.getFinalScore(), 1e-9);
        assertTrue(trace.getAugmentedMessage().contains("[历史参考"));
        assertTrue(trace.toOutcome().isRecalled());
        assertEquals("退款问题", trace.toOutcome().getQuery());
    }

    @Test
    public void traceForChat_embeddingFailure_returnsErrorWithoutThrowing() {
        when(embeddingClient.embed("退款问题")).thenThrow(new IllegalStateException("ark down"));

        RecallTrace trace = recaller.traceForChat("退款问题", null);

        assertEquals(RecallStatus.ERROR, trace.getStatus());
        assertEquals("IllegalStateException", trace.getErrorType());
        assertEquals("ark down", trace.getErrorMessage());
        assertEquals("退款问题", trace.getAugmentedMessage());
        assertTrue(trace.getHits().isEmpty());
    }

    @Test
    public void traceForChat_badChunkVector_isCountedWithoutFailingWholeRecall() {
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk good = chunkOf("good", new float[]{0.9f, 0.1f, 0f}, "好");
        RagChunk bad = chunkOf("bad", new float[]{1f, 0f}, "坏");
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(bad, good));

        RecallTrace trace = recaller.traceForChat("q", null);

        assertEquals(RecallStatus.HIT, trace.getStatus());
        assertEquals(1, trace.getStats().getBadVectorCount());
        assertEquals(2, trace.getStats().getFilteredCount());
        assertEquals(1, trace.getStats().getRankedCount());
        assertEquals("good", trace.getHits().get(0).getChunkId());
    }

    @Test
    public void traceForChat_workspaceMinTier_blocksExploratoryChunks() {
        props.getRecall().setCrossSourceEnabled(true);
        when(workspaceResolver.resolve("/ws/project")).thenReturn(contextWithMinTier(
                com.example.agentweb.domain.refinery.TrustTier.PENDING));
        when(embeddingClient.embed("退款问题")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk exploratory = chunkOfWithSource("c-chat", new float[]{0.99f, 0.1f, 0f},
                "chat经验", SourceType.CHAT, TrustTier.EXPLORATORY);
        RagChunk verified = chunkOfWithSource("c-diag", new float[]{0.9f, 0.1f, 0f},
                "诊断结论", SourceType.DIAGNOSE, TrustTier.VERIFIED);
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(exploratory, verified));

        RecallTrace trace = recaller.traceForChat("退款问题", "/ws/project");

        assertEquals(RecallStatus.HIT, trace.getStatus());
        assertEquals(1, trace.getHits().size());
        assertEquals("c-diag", trace.getHits().get(0).getChunkId(),
                "min_tier=PENDING 应放行 VERIFIED、拦截 EXPLORATORY");
    }

    @Test
    public void traceForChat_noWorkspaceMinTier_keepsExploratory() {
        when(workspaceResolver.resolve("/ws/other")).thenReturn(contextWithMinTier(null));
        when(embeddingClient.embed("退款问题")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk exploratory = chunkOfWithSource("c-chat", new float[]{0.99f, 0.1f, 0f},
                "chat经验", SourceType.CHAT, TrustTier.EXPLORATORY);
        when(chunkRepo.findActive(NOW)).thenReturn(Collections.singletonList(exploratory));

        RecallTrace trace = recaller.traceForChat("退款问题", "/ws/other");

        assertEquals(RecallStatus.HIT, trace.getStatus());
        assertEquals("c-chat", trace.getHits().get(0).getChunkId(),
                "未声明 min_tier 的 workspace 保持现状");
    }

    @Test
    public void recall_tierSpecificVectorFloor_blocksLowCosineExploratoryOnly() {
        props.getRecall().setMinVectorScore(0.6d);
        props.getRecall().getMinVectorScoreByTier()
                .put(TrustTier.EXPLORATORY, 0.9d);
        props.getRecall().setCrossSourceEnabled(true);
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk exploratory = chunkOfWithSource("c-chat", new float[]{0.8f, 0.6f, 0f},
                "chat经验", SourceType.CHAT, TrustTier.EXPLORATORY);
        RagChunk verified = chunkOfWithSource("c-diag", new float[]{0.8f, 0.6f, 0f},
                "诊断结论", SourceType.DIAGNOSE, TrustTier.VERIFIED);
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(exploratory, verified));

        List<RagChunk> hits = recaller.recall("q", 5);

        assertEquals(1, hits.size(), "同余弦下 EXPLORATORY 应被更高分层闸拦住");
        assertEquals("c-diag", hits.get(0).getId());
    }

    private com.example.agentweb.app.agentrun.WorkspaceContext contextWithMinTier(
            com.example.agentweb.domain.refinery.TrustTier minTier) {
        return new com.example.agentweb.app.agentrun.WorkspaceContext(
                java.nio.file.Paths.get("/ws"), java.nio.file.Paths.get("/ws"), null, null,
                Collections.emptyList(), Collections.emptyMap(), minTier);
    }

    @Test
    public void recall_相对阈值_截掉与最佳命中不同档的弱相关() {
        props.getRecall().getRanking().setVectorWeight(1d);
        props.getRecall().getRanking().setSignalWeight(0d);
        props.getRecall().getRanking().setTimeDecayWeight(0d);
        props.getRecall().setMinScoreRatio(0.5d);
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk strong = chunkOf("strong", new float[]{1f, 0f, 0f}, "强");       // cos 1.0
        RagChunk mid = chunkOf("mid", new float[]{0.8f, 0.6f, 0f}, "中");          // cos 0.8
        RagChunk weak = chunkOf("weak", new float[]{0.3f, 0.954f, 0f}, "弱");      // cos ~0.3 < 0.5*top
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(weak, strong, mid));

        List<RagChunk> hits = recaller.recall("q", 3);

        assertEquals(2, hits.size(), "弱相关(<最佳命中一半)应被截掉, 不凑满 topK");
        assertEquals("strong", hits.get(0).getId());
        assertEquals("mid", hits.get(1).getId());
    }

    @Test
    public void recall_绝对阈值_截掉低于下限的命中() {
        props.getRecall().getRanking().setVectorWeight(1d);
        props.getRecall().getRanking().setSignalWeight(0d);
        props.getRecall().getRanking().setTimeDecayWeight(0d);
        props.getRecall().setMinScore(0.5d);
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk hi = chunkOf("hi", new float[]{1f, 0f, 0f}, "高");                // 1.0
        RagChunk lo = chunkOf("lo", new float[]{0.3f, 0.954f, 0f}, "低");          // 0.3 < 0.5
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(lo, hi));

        List<RagChunk> hits = recaller.recall("q", 3);

        assertEquals(1, hits.size());
        assertEquals("hi", hits.get(0).getId());
    }

    @Test
    public void recall_阈值默认关_弱相关仍按topK返回不被截() {
        // 默认 minScore=minScoreRatio=0: 行为与纯 topK 截断一致, 不因新增闸门改变既有召回
        props.getRecall().getRanking().setVectorWeight(1d);
        props.getRecall().getRanking().setSignalWeight(0d);
        props.getRecall().getRanking().setTimeDecayWeight(0d);
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk strong = chunkOf("strong", new float[]{1f, 0f, 0f}, "强");
        RagChunk weak = chunkOf("weak", new float[]{0.1f, 0.995f, 0f}, "弱");      // cos 0.1
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(weak, strong));

        List<RagChunk> hits = recaller.recall("q", 3);

        assertEquals(2, hits.size(), "默认不开阈值, 弱相关也应保留");
    }

    @Test
    public void recall_余弦硬闸_全场都是噪声时一条都不召回() {
        // 病根复现: 库里全是弱相关, 即便最佳命中余弦也低于硬闸 → 整个召回为空,
        // 根治"相对闸地板被低 topScore 压低、3 条噪声照样塞满 topK"
        props.getRecall().getRanking().setVectorWeight(0.7d);
        props.getRecall().getRanking().setSignalWeight(0.2d);
        props.getRecall().getRanking().setTimeDecayWeight(0.1d);
        props.getRecall().setMinVectorScore(0.5d);
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk weak1 = chunkOf("w1", new float[]{0.3f, 0.954f, 0f}, "弱1");   // cos ~0.30
        RagChunk weak2 = chunkOf("w2", new float[]{0.2f, 0.98f, 0f}, "弱2");    // cos ~0.20
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(weak1, weak2));

        List<RagChunk> hits = recaller.recall("q", 3);

        assertTrue(hits.isEmpty(), "余弦全低于硬闸, 不应凑数召回噪声");
    }

    @Test
    public void recall_余弦硬闸_只留余弦达标的命中() {
        props.getRecall().getRanking().setVectorWeight(1d);
        props.getRecall().getRanking().setSignalWeight(0d);
        props.getRecall().getRanking().setTimeDecayWeight(0d);
        props.getRecall().setMinVectorScore(0.5d);
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk good = chunkOf("good", new float[]{0.9f, 0.2f, 0f}, "好");      // cos ~0.98
        RagChunk noise = chunkOf("noise", new float[]{0.3f, 0.954f, 0f}, "噪");  // cos ~0.30
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(noise, good));

        List<RagChunk> hits = recaller.recall("q", 3);

        assertEquals(1, hits.size(), "余弦低于硬闸的噪声应在融合排序前出局");
        assertEquals("good", hits.get(0).getId());
    }

    @Test
    public void recall_中文中夹的英文关键词_被切出并精确命中signal() {
        // 旧分词按空白/标点切→把 "查expireAmount" 黏成一个 token, 与 signal "expireAmount" 不相等→Jaccard 0;
        // 新分词把 ASCII 连续段单独切出, expireamount 精确命中
        props.getRecall().getRanking().setVectorWeight(0d);
        props.getRecall().getRanking().setSignalWeight(1d);
        props.getRecall().getRanking().setTimeDecayWeight(0d);
        when(embeddingClient.embed("查expireAmount")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk match = chunkOf("match", new float[]{0f, 1f, 0f}, "过期金额",
                Arrays.asList("expireAmount"));
        RagChunk noMatch = chunkOf("noMatch", new float[]{0.99f, 0.1f, 0f}, "无关",
                Arrays.asList("余额"));
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(noMatch, match));

        List<RagChunk> hits = recaller.recall("查expireAmount", 2);

        assertEquals("match", hits.get(0).getId(), "夹在中文里的 expireAmount 应被切出并精确命中 signal");
    }

    @Test
    public void recall_cross_source_disabled_should_only_return_chat_chunks() {
        props.getRecall().setCrossSourceEnabled(false);
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk chatChunk = chunkOfWithSource("chat-1", new float[]{0.9f, 0.1f, 0f},
                "chat结论", SourceType.CHAT, TrustTier.EXPLORATORY);
        RagChunk diagVerified = chunkOfWithSource("diag-1", new float[]{0.95f, 0.05f, 0f},
                "诊断结论", SourceType.DIAGNOSE, TrustTier.VERIFIED);
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(chatChunk, diagVerified));

        List<RagChunk> hits = recaller.recall("q", 5);

        assertEquals(1, hits.size());
        assertEquals("chat-1", hits.get(0).getId());
    }

    @Test
    public void recall_cross_source_enabled_should_include_diagnose_verified_chunks() {
        props.getRecall().setCrossSourceEnabled(true);
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk chatChunk = chunkOfWithSource("chat-1", new float[]{0.8f, 0.1f, 0f},
                "chat结论", SourceType.CHAT, TrustTier.EXPLORATORY);
        RagChunk diagVerified = chunkOfWithSource("diag-v", new float[]{0.95f, 0.05f, 0f},
                "诊断高可信", SourceType.DIAGNOSE, TrustTier.VERIFIED);
        RagChunk diagPending = chunkOfWithSource("diag-p", new float[]{0.99f, 0.01f, 0f},
                "诊断待验证", SourceType.DIAGNOSE, TrustTier.PENDING);
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(chatChunk, diagVerified, diagPending));

        List<RagChunk> hits = recaller.recall("q", 5);

        assertEquals(2, hits.size());
        List<String> ids = Arrays.asList(hits.get(0).getId(), hits.get(1).getId());
        assertTrue(ids.contains("chat-1"));
        assertTrue(ids.contains("diag-v"));
        assertFalse(ids.contains("diag-p"));
    }

    @Test
    public void recall_higher_ingest_score_breaks_tie_when_cosine_equal() {
        // 同 cosine、同 signal、同 createdAt 时, 唯一差异是入库 score → 高 score 应排前。
        // 这是 score 进入排序的最基本保证(任意 γ>0 都成立), 守住"质量分至少能破同分平局"。
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk hi = chunkWithScore("hi", new float[]{0.8f, 0.6f, 0f}, 0.9d);   // cos 0.8
        RagChunk lo = chunkWithScore("lo", new float[]{0.8f, 0.6f, 0f}, 0.5d);   // cos 0.8, 同向量
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(lo, hi));

        List<RagChunk> hits = recaller.recall("q", 2);

        assertEquals("hi", hits.get(0).getId(), "cosine 相同, 高入库 score 应排前");
        assertEquals("lo", hits.get(1).getId());
    }

    @Test
    public void recall_default_gamma_lets_high_score_overcome_small_cosine_deficit() {
        // P0 去稀释: γ 默认 0.1→0.2 后, 高 score(1.0)的 chunk 即便 cosine 略低(0.75 vs 0.85),
        // 也能在排序上反超低 score(0.5)的高 cosine chunk。验"入库质量分到排序端真正生效"。
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk highCosLowScore = chunkWithScore("highCos",
                new float[]{0.85f, 0.5268f, 0f}, 0.5d);   // cos 0.85
        RagChunk lowCosHighScore = chunkWithScore("lowCos",
                new float[]{0.75f, 0.6614f, 0f}, 1.0d);   // cos 0.75
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(highCosLowScore, lowCosHighScore));

        List<RagChunk> hits = recaller.recall("q", 2);

        assertEquals("lowCos", hits.get(0).getId(),
                "γ=0.2 默认下, 高入库 score 应能补足 0.1 的 cosine 差距反超");
    }

    @Test
    public void recall_old_gamma_0_1_cosine_still_dominates_same_score_gap() {
        // 对照组: 把 γ 调回旧默认 0.1, 同一组 chunk 下高 cosine 重新胜出——
        // 证明上一条的反超确实由 γ 0.1→0.2 这次去稀释造成, 而非测试构造巧合。
        props.getRecall().getRanking().setTimeDecayWeight(0.1d);
        when(embeddingClient.embed("q")).thenReturn(new float[]{1f, 0f, 0f});
        RagChunk highCosLowScore = chunkWithScore("highCos",
                new float[]{0.85f, 0.5268f, 0f}, 0.5d);   // cos 0.85
        RagChunk lowCosHighScore = chunkWithScore("lowCos",
                new float[]{0.75f, 0.6614f, 0f}, 1.0d);   // cos 0.75
        when(chunkRepo.findActive(NOW)).thenReturn(Arrays.asList(highCosLowScore, lowCosHighScore));

        List<RagChunk> hits = recaller.recall("q", 2);

        assertEquals("highCos", hits.get(0).getId(),
                "γ=0.1 旧默认下, score 被稀释, 0.1 的 cosine 差距仍主导排序");
    }

    private RagChunk chunkWithScore(String id, float[] vec, double score) {
        return RagChunk.builder()
                .id(id)
                .sourceSessionId("sess-x")
                .agentType(AgentType.CLAUDE)
                .content(new RefinedContent("t", Arrays.asList("signal"),
                        "ctx", "1) 2) 3)", "conclusion-" + id))
                .score(score)
                .ttlCategory(TtlCategory.GENERAL)
                .createdAt(NOW.minusSeconds(86400))
                .embeddingModel("doubao-embedding-vision")
                .embedding(vec)
                .build();
    }

    private RagChunk chunkOfWithSource(String id, float[] vec, String title,
                                           SourceType sourceType, TrustTier tier) {
        return RagChunk.builder()
                .id(id)
                .sourceSessionId("sess-x")
                .agentType(AgentType.CLAUDE)
                .content(new RefinedContent(title, Arrays.asList("signal"),
                        "ctx", "process", "conclusion-" + id))
                .score(0.8)
                .ttlCategory(TtlCategory.GENERAL)
                .createdAt(NOW.minusSeconds(86400))
                .embeddingModel("doubao-embedding-vision")
                .embedding(vec)
                .sourceType(sourceType)
                .tier(tier)
                .build();
    }

    private RagChunk chunkOf(String id, float[] vec, String title) {
        return chunkOf(id, vec, title, Arrays.asList("signal"));
    }

    private RagChunk chunkOf(String id, float[] vec, String title, List<String> signals) {
        return RagChunk.builder()
                .id(id)
                .sourceSessionId("sess-x")
                .agentType(AgentType.CLAUDE)
                .content(new RefinedContent(title, signals,
                        "ctx", "1) 2) 3)", "conclusion-" + id))
                .score(0.8)
                .ttlCategory(TtlCategory.GENERAL)
                .createdAt(NOW.minusSeconds(86400))
                .embeddingModel("doubao-embedding-vision")
                .embedding(vec)
                .build();
    }
}
