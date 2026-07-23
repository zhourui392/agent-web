package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.refinery.DiscardedRefineRecord;
import com.example.agentweb.domain.refinery.DiscardedRefineRepository;
import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.SessionRefineryState;
import com.example.agentweb.domain.refinery.SessionRefineryStateRepository;
import com.example.agentweb.domain.refinery.ConversationTurn;
import com.example.agentweb.domain.refinery.ConversationView;
import com.example.agentweb.domain.refinery.EmbeddingClient;
import com.example.agentweb.domain.refinery.RefinedContent;
import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.domain.refinery.TrustTierPolicy;
import com.example.agentweb.domain.refinery.TtlCategory;
import com.example.agentweb.config.refinery.RefineryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RefineryAppService} 的默认实现.
 *
 * <p>编排顺序: 加载 {@link ConversationView} → refine (LLM 评分) → 阈值检查 → embed → 建 chunk → 落库.</p>
 *
 * <p>任意阶段失败都更新 {@code chat_session_rag_state}, 防止 scheduler 重入.
 * 不对失败重试, 等会话有新消息推进 last_message_at 后下一轮再评.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
@Service
@ConditionalOnProperty(prefix = "agent.refinery", name = "enabled", havingValue = "true")
@Slf4j
public class RefineryAppServiceImpl implements RefineryAppService {

    private static final long SECONDS_PER_DAY = 86400L;

    private final ChatViewBuilder viewBuilder;
    private final ConversationRefinery refinery;
    private final TrustTierPolicy tierPolicy;
    private final EmbeddingClient embeddingClient;
    private final RagChunkRepository chunkRepo;
    private final SessionRefineryStateRepository stateRepo;
    private final DiscardedRefineRepository discardedRepo;
    private final RefineryProperties props;
    private final Clock clock;

    public RefineryAppServiceImpl(ChatViewBuilder viewBuilder,
                                 ConversationRefinery refinery,
                                 TrustTierPolicy tierPolicy,
                                 EmbeddingClient embeddingClient,
                                 RagChunkRepository chunkRepo,
                                 SessionRefineryStateRepository stateRepo,
                                 DiscardedRefineRepository discardedRepo,
                                 RefineryProperties props,
                                 @Qualifier("chatRagClock") Clock clock) {
        this.viewBuilder = viewBuilder;
        this.refinery = refinery;
        this.tierPolicy = tierPolicy;
        this.embeddingClient = embeddingClient;
        this.chunkRepo = chunkRepo;
        this.stateRepo = stateRepo;
        this.discardedRepo = discardedRepo;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public boolean refineAndIngest(String sessionId) {
        Optional<ConversationView> viewOpt = viewBuilder.build(sessionId);
        if (!viewOpt.isPresent()) {
            log.debug("refinery-ingest-skip-view-unavailable sessionId={}", sessionId);
            return false;
        }
        ConversationView view = viewOpt.get();
        if (!tierPolicy.shouldIngest(view.getSourceType(), view.getVerdict())) {
            log.info("refinery-ingest-skip-policy sourceId={} sourceType={} verdict={}",
                    sessionId, view.getSourceType(), view.getVerdict());
            return false;
        }
        Instant lastMessageSeen = lastTurnInstant(view);
        Optional<SessionRefineryState> existing = stateRepo.findBySessionId(sessionId);
        if (shouldSkip(existing, lastMessageSeen)) {
            log.debug("refinery-ingest-skip-already-processed sessionId={} lastMsg={}",
                    sessionId, lastMessageSeen);
            return false;
        }
        try {
            IngestOutcome outcome = ingestCore(view);
            if (outcome.belowThreshold) {
                log.info("refinery-ingest-below-threshold sessionId={} score={} threshold={}",
                        sessionId, outcome.score, props.getRefine().getScoreThreshold());
                // below-threshold 是有意决策, 不重试 → retryCount 归零
                writeState(sessionId, lastMessageSeen, null,
                        SessionRefineryState.LAST_ERROR_BELOW_THRESHOLD, 0);
                return false;
            }
            writeState(sessionId, lastMessageSeen, outcome.chunkId, null, 0);
            log.info("refinery-ingest-saved sessionId={} chunkId={} score={} ttl={}",
                    sessionId, outcome.chunkId, outcome.score, outcome.ttlCategory);
            return true;
        } catch (RuntimeException e) {
            int nextRetry = existing.map(SessionRefineryState::getRetryCount).orElse(0) + 1;
            log.warn("refinery-ingest-failed sessionId={} retryCount={} reason={}",
                    sessionId, nextRetry, e.getMessage(), e);
            writeState(sessionId, lastMessageSeen, null, e.getMessage(), nextRetry);
            return false;
        }
    }

    @Override
    public Optional<String> ingest(ConversationView view) {
        if (!tierPolicy.shouldIngest(view.getSourceType(), view.getVerdict())) {
            log.info("refinery-ingest-skip-policy sourceId={} sourceType={} verdict={}",
                    view.getSourceId(), view.getSourceType(), view.getVerdict());
            return Optional.empty();
        }
        IngestOutcome outcome = ingestCore(view);
        if (outcome.belowThreshold) {
            log.info("refinery-ingest-below-threshold sourceId={} score={} threshold={}",
                    view.getSourceId(), outcome.score, props.getRefine().getScoreThreshold());
            return Optional.empty();
        }
        log.info("refinery-ingest-saved sourceId={} sourceType={} chunkId={} score={}",
                view.getSourceId(), view.getSourceType(), outcome.chunkId, outcome.score);
        return Optional.of(outcome.chunkId);
    }

    /**
     * 源类型无关的 ingest 核心逻辑: refine → 阈值检查 → embed → buildChunk → save.
     * 不写任何状态表; 失败上抛供调用方记录.
     */
    private IngestOutcome ingestCore(ConversationView view) {
        RefineResult result = refinery.refine(view);
        if (result.getScore() < props.getRefine().getScoreThreshold()) {
            persistDiscardedIfEnabled(view, result);
            return IngestOutcome.belowThreshold(result.getScore());
        }
        float[] vec = embeddingClient.embed(buildEmbedText(result.getContent()));
        RagChunk chunk = buildChunk(view, result, vec);
        chunkRepo.save(chunk);
        return IngestOutcome.saved(chunk.getId(), result.getScore(), result.getTtlCategory());
    }

    /** 内部传递 ingestCore 结果的 VO. */
    private static final class IngestOutcome {
        final boolean belowThreshold;
        final String chunkId;
        final double score;
        final TtlCategory ttlCategory;

        private IngestOutcome(boolean belowThreshold, String chunkId, double score, TtlCategory ttl) {
            this.belowThreshold = belowThreshold;
            this.chunkId = chunkId;
            this.score = score;
            this.ttlCategory = ttl;
        }

        static IngestOutcome belowThreshold(double score) {
            return new IngestOutcome(true, null, score, null);
        }

        static IngestOutcome saved(String chunkId, double score, TtlCategory ttl) {
            return new IngestOutcome(false, chunkId, score, ttl);
        }
    }

    /**
     * 把 below-threshold 的会话留痕到 chat_rag_discarded, 供管理台"已丢弃(低分)"展示与阈值校准.
     * 纯辅助旁路: 失败仅 warn, 绝不影响 below-threshold 主流程 (调用方仍正常 writeState + 返回).
     * 由 {@code agent.refinery.refine.persist-discarded} 控制, 默认开.
     */
    private void persistDiscardedIfEnabled(ConversationView view, RefineResult result) {
        if (!props.getRefine().isPersistDiscarded()) {
            return;
        }
        try {
            discardedRepo.save(DiscardedRefineRecord.builder()
                    .id(UUID.randomUUID().toString())
                    .sourceType(view.getSourceType())
                    .sourceSessionId(view.getSourceId())
                    .title(result.getContent().getTitle())
                    .conclusion(result.getContent().getConclusion())
                    .ttlCategory(result.getTtlCategory())
                    .score(result.getScore())
                    .threshold(props.getRefine().getScoreThreshold())
                    .agentType(view.getAgentType() == null ? null : view.getAgentType().name())
                    .env(view.getEnv())
                    .createdAt(clock.instant())
                    .reason(DiscardedRefineRecord.REASON_BELOW_THRESHOLD)
                    .build());
        } catch (RuntimeException e) {
            log.warn("refinery-discarded-persist-failed sourceId={} score={} reason={}",
                    view.getSourceId(), result.getScore(), e.getMessage(), e);
        }
    }

    /**
     * 是否跳过本次评分. 与调度器 SQL ({@code findIdsWithLastMessageBefore}) 的入选条件互补:
     * 只有"成功"或"below-threshold"且无新消息时才跳过; 真·失败 (last_error 非 null 且非
     * below-threshold) 即使无新消息也放行, 由调度器的 retry_count 上限统一节流, 此处不再二次拦截.
     *
     * <p>"有新消息"的判定按<b>秒精度</b>比较: {@code chat_session.last_message_at} 在历史数据中
     * 可能为秒精度, 而 {@code last_message_at_seen} 为毫秒, 直接 equals 会让旧会话被误判为
     * "有新消息" 而永远重评. 与 SQL 侧的 {@code /1000} 归一保持一致.</p>
     */
    private boolean shouldSkip(Optional<SessionRefineryState> existing, Instant lastMessageSeen) {
        if (!existing.isPresent()) {
            return false;
        }
        SessionRefineryState state = existing.get();
        Instant seenSec = state.getLastMessageAtSeen().truncatedTo(ChronoUnit.SECONDS);
        Instant currentSec = lastMessageSeen.truncatedTo(ChronoUnit.SECONDS);
        if (!seenSec.equals(currentSec)) {
            return false;
        }
        boolean realError = state.getLastError() != null
                && !SessionRefineryState.LAST_ERROR_BELOW_THRESHOLD.equals(state.getLastError());
        return !realError;
    }

    private RagChunk buildChunk(ConversationView view, RefineResult result, float[] vec) {
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(ttlDays(result.getTtlCategory()) * SECONDS_PER_DAY);
        TrustTier tier = tierPolicy.decide(view.getSourceType(), view.getVerdict());
        return RagChunk.builder()
                .id(UUID.randomUUID().toString())
                .sourceSessionId(view.getSourceId())
                .sourceMsgRange("msg_1..msg_" + view.getTurns().size())
                .agentType(view.getAgentType())
                .content(result.getContent())
                .score(result.getScore())
                .ttlCategory(result.getTtlCategory())
                .createdAt(now)
                .expiresAt(expiresAt)
                .embeddingModel(embeddingClient.modelName())
                .embedding(vec)
                .sourceType(view.getSourceType())
                .tier(tier)
                .env(view.getEnv())
                .build();
    }

    private String buildEmbedText(RefinedContent content) {
        StringBuilder sb = new StringBuilder();
        sb.append(content.getTitle());
        if (content.getTriggerSignals() != null && !content.getTriggerSignals().isEmpty()) {
            sb.append('\n').append(String.join(" ", content.getTriggerSignals()));
        }
        if (!content.getTriggerDescription().isEmpty()) {
            sb.append('\n').append(content.getTriggerDescription());
        }
        if (!content.getContext().isEmpty()) {
            sb.append('\n').append(content.getContext());
        }
        if (!content.getConclusion().isEmpty()) {
            sb.append('\n').append(content.getConclusion());
        }
        return sb.toString();
    }

    /**
     * 存量 chunk 重嵌入（M4 triggerDescription 迁移）：embed 文本构成变化后按批渐进刷新向量。
     * 回滚说明：同模型同维度只是文本构成变化，不刷新也兼容（仅少 triggerDescription 增益），
     * 回滚 = 停止调用，无 schema 破坏。
     *
     * @param limit 本批最多处理条数（防长事务，管理台分批触发）
     * @return 实际刷新条数
     */
    @Override
    public int reembedActive(int limit) {
        List<RagChunk> active = chunkRepo.findActive(clock.instant());
        int refreshed = 0;
        for (RagChunk chunk : active) {
            if (refreshed >= limit) {
                break;
            }
            try {
                float[] vec = embeddingClient.embed(buildEmbedText(chunk.getContent()));
                if (chunkRepo.updateEmbedding(chunk.getId(), vec, embeddingClient.modelName())) {
                    refreshed++;
                }
            } catch (RuntimeException e) {
                log.warn("refinery-reembed-failed chunkId={} reason={}", chunk.getId(), e.getMessage());
            }
        }
        log.info("refinery-reembed-batch refreshed={} activeTotal={} limit={}",
                refreshed, active.size(), limit);
        return refreshed;
    }

    @Override
    public RefineryDeleteResult deleteChunk(String id) {
        return new RefineryDeleteResult(id, chunkRepo.deleteById(id));
    }

    @Override
    public RefineryDeleteResult deleteDiscarded(String id) {
        return new RefineryDeleteResult(id, discardedRepo.deleteById(id));
    }

    private long ttlDays(TtlCategory category) {
        switch (category) {
            case CODE:
                return props.getTtl().getCodeDays();
            case DEPLOY:
                return props.getTtl().getDeployDays();
            case BUSINESS:
                return props.getTtl().getBusinessDays();
            case GENERAL:
            default:
                return props.getTtl().getGeneralDays();
        }
    }

    private Instant lastTurnInstant(ConversationView view) {
        List<ConversationTurn> turns = view.getTurns();
        return turns.get(turns.size() - 1).getTimestamp();
    }

    private void writeState(String sessionId, Instant lastMessageSeen,
                            String chunkId, String error, int retryCount) {
        stateRepo.save(new SessionRefineryState(
                sessionId, clock.instant(), lastMessageSeen, chunkId, error, retryCount));
    }
}
