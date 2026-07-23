package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.CosineSimilarity;
import com.example.agentweb.domain.refinery.EmbeddingClient;
import com.example.agentweb.app.agentrun.WorkspaceContext;
import com.example.agentweb.app.agentrun.WorkspaceContextResolver;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.config.refinery.RefineryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Retrieval and prompt-augmentation service for chat-session vector recall.
 * The frontend "RAG recall" switch drives this path: when enabled, each user
 * message is used as the query and historical references are prepended to the
 * text sent to the CLI.
 *
 * <p>Public entry points:</p>
 * <ul>
 *   <li>{@link #recall(String, int)}: vector recall plus reranking, returning sorted chunks.</li>
 *   <li>{@link #traceForChat(String)}: chat recall plus prompt augmentation and observability facts.</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
@Component
@ConditionalOnProperty(prefix = "agent.refinery", name = "enabled", havingValue = "true")
@Slf4j
public class RefineryRecaller {

    private final EmbeddingClient embeddingClient;
    private final RagChunkRepository chunkRepo;
    private final RefineryProperties props;
    private final Clock clock;
    private final RecallDetailStore detailStore;
    private final WorkspaceContextResolver workspaceResolver;

    public RefineryRecaller(EmbeddingClient embeddingClient,
                           RagChunkRepository chunkRepo,
                           RefineryProperties props,
                           @Qualifier("chatRagClock") Clock clock,
                           RecallDetailStore detailStore,
                           WorkspaceContextResolver workspaceResolver) {
        this.embeddingClient = embeddingClient;
        this.chunkRepo = chunkRepo;
        this.props = props;
        this.clock = clock;
        this.detailStore = detailStore;
        this.workspaceResolver = workspaceResolver;
    }

    public List<RagChunk> recall(String query, int topK) {
        return recallWithFilter(query, topK, buildChatFilter(null)).chunks();
    }

    private RecallRun recallWithFilter(String query, int topK,
                                                java.util.function.Predicate<RagChunk> filter) {
        float[] queryVec = embeddingClient.embed(query);
        Instant now = clock.instant();
        List<RagChunk> active = chunkRepo.findActive(now);
        Set<String> queryTokens = tokenize(query);
        RefineryProperties.Recall recall = props.getRecall();
        RefineryProperties.Recall.Ranking weights = recall.getRanking();
        double halfLifeDays = recall.getHalfLifeDays();
        double minVectorScore = recall.getMinVectorScore();
        int belowVectorFloor = 0;
        int filteredCount = 0;
        int badVectorCount = 0;
        Double topVectorScore = null;
        List<Scored> scored = new ArrayList<>(active.size());
        for (RagChunk chunk : active) {
            if (!filter.test(chunk)) {
                continue;
            }
            filteredCount++;
            try {
                double vectorSim = CosineSimilarity.cosine(queryVec, chunk.getEmbedding());
                topVectorScore = topVectorScore == null ? vectorSim : Math.max(topVectorScore, vectorSim);
                // 余弦硬闸(按 tier 分层, 低可信更高门槛): 语义不相关的 chunk 在融合排序前直接出局
                double vectorFloor = vectorFloor(recall, chunk.getTier());
                if (vectorFloor > 0d && vectorSim < vectorFloor) {
                    belowVectorFloor++;
                    continue;
                }
                ScoreParts parts = scoreParts(chunk, vectorSim, queryTokens,
                        weights, halfLifeDays, now);
                scored.add(new Scored(chunk, parts));
            } catch (RuntimeException e) {
                badVectorCount++;
                log.warn("refinery-recall-skip-bad-vector chunkId={} reason={}",
                        chunk.getId(), e.getMessage());
            }
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        List<Scored> result = applyThresholdAndTopK(scored, topK);
        if (log.isDebugEnabled()) {
            double topScore = scored.isEmpty() ? 0d : scored.get(0).score;
            double topCosine = topVectorScore == null ? 0d : topVectorScore;
            log.debug("refinery-recall active={} filtered={} belowVecFloor={} badVector={} ranked={} kept={} "
                            + "topScore={} topCosine={} minVec={} minScore={} minRatio={}",
                    active.size(), filteredCount, belowVectorFloor, badVectorCount, scored.size(), result.size(),
                    topScore, topCosine, minVectorScore,
                    recall.getMinScore(), recall.getMinScoreRatio());
        }
        return new RecallRun(result, new RecallStats(
                active.size(),
                filteredCount,
                belowVectorFloor,
                scored.size(),
                badVectorCount,
                topVectorScore,
                scored.isEmpty() ? null : scored.get(0).score,
                topK,
                false,
                recall.isCrossSourceEnabled(),
                halfLifeDays,
                minVectorScore,
                recall.getMinScore(),
                recall.getMinScoreRatio(),
                weights.getVectorWeight(),
                weights.getSignalWeight(),
                weights.getTimeDecayWeight(),
                props.getEmbedding().getModel(),
                props.getEmbedding().getDimension()));
    }

    /**
     * 在已降序排好的候选上套相关性阈值再取 topK. 两道闸 (绝对 {@code minScore} + 相对
     * {@code minScoreRatio}) 默认都关 (配置 {@code <=0}), 此时行为与纯 topK 截断一致。
     *
     * <p>因候选已降序, 任一闸第一次不满足即可 {@code break}——后续分只会更低。相对闸只在
     * {@code topScore>0} 时生效, 避免最高分为负时把基准 (topScore*ratio) 抬到比 topScore 还高、
     * 反而把唯一相关的命中也截掉。</p>
     */
    private List<Scored> applyThresholdAndTopK(List<Scored> scored, int topK) {
        RefineryProperties.Recall recall = props.getRecall();
        double minScore = recall.getMinScore();
        double minScoreRatio = recall.getMinScoreRatio();
        double topScore = scored.isEmpty() ? 0d : scored.get(0).score;
        double relativeFloor = (minScoreRatio > 0d && topScore > 0d)
                ? topScore * minScoreRatio : Double.NEGATIVE_INFINITY;
        List<Scored> result = new ArrayList<>(Math.min(topK, scored.size()));
        for (Scored s : scored) {
            // 候选已降序: 任一闸首次不满足, 后续分只会更低, 直接停
            if (result.size() >= topK) {
                break;
            }
            if (minScore > 0d && s.score < minScore) {
                break;
            }
            if (s.score < relativeFloor) {
                break;
            }
            result.add(s);
        }
        return result;
    }

    private ScoreParts scoreParts(RagChunk chunk, double vectorSim, Set<String> queryTokens,
                              RefineryProperties.Recall.Ranking weights, double halfLifeDays,
                              Instant now) {
        double jaccard = signalJaccard(queryTokens, chunk.getContent().getTriggerSignals());
        double decay = timeDecay(chunk.getCreatedAt(), now, halfLifeDays);
        double timeScore = decay * chunk.getScore();
        double finalScore = weights.getVectorWeight() * vectorSim
                + weights.getSignalWeight() * jaccard
                + weights.getTimeDecayWeight() * timeScore;
        return new ScoreParts(finalScore, vectorSim, jaccard, timeScore);
    }

    /**
     * 分词: 给 signal Jaccard 用。旧实现只按空白/ASCII 标点切, 中文整段无分隔符会黏成一个巨型
     * token, 像 {@code expireAmount} 这种精确关键词被吸进周围中文里永远命不中。现在分两路:
     * <ul>
     *   <li>ASCII 字母/数字连续段各自成词 (大小写归一)——{@code expireAmount→expireamount}、错误码、ID 精确可比</li>
     *   <li>CJK (中日韩/假名/谚文等非 ASCII 表意字符) 取<b>相邻二元组</b>, 单字时退化为单字——
     *       中文无空格切不出整词, 二元组兼顾判别力与召回</li>
     * </ul>
     * query 与 signal 走同一套规则, 保证可比。
     */
    private Set<String> tokenize(String text) {
        Set<String> sink = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return sink;
        }
        String lower = text.toLowerCase();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isAsciiAlphaNum(c)) {
                flushCjkBigrams(cjk, sink);
                ascii.append(c);
            } else if (isIdeographic(c)) {
                flushAscii(ascii, sink);
                cjk.append(c);
            } else {
                flushAscii(ascii, sink);
                flushCjkBigrams(cjk, sink);
            }
        }
        flushAscii(ascii, sink);
        flushCjkBigrams(cjk, sink);
        return sink;
    }

    private static boolean isAsciiAlphaNum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isIdeographic(char c) {
        return c > 0x7F && Character.isLetter(c);
    }

    private static void flushAscii(StringBuilder buf, Set<String> sink) {
        if (buf.length() > 0) {
            sink.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static void flushCjkBigrams(StringBuilder buf, Set<String> sink) {
        int n = buf.length();
        if (n == 1) {
            sink.add(buf.toString());
        } else {
            for (int i = 0; i + 1 < n; i++) {
                sink.add(buf.substring(i, i + 2));
            }
        }
        buf.setLength(0);
    }

    private double signalJaccard(Set<String> queryTokens, List<String> signals) {
        if (queryTokens.isEmpty() || signals == null || signals.isEmpty()) {
            return 0d;
        }
        Set<String> signalTokens = new HashSet<>();
        for (String s : signals) {
            signalTokens.addAll(tokenize(s));
        }
        if (signalTokens.isEmpty()) {
            return 0d;
        }
        Set<String> intersection = new HashSet<>(queryTokens);
        intersection.retainAll(signalTokens);
        Set<String> union = new HashSet<>(queryTokens);
        union.addAll(signalTokens);
        return (double) intersection.size() / union.size();
    }

    private double timeDecay(Instant createdAt, Instant now, double halfLifeDays) {
        if (halfLifeDays <= 0d) {
            return 1d;
        }
        double ageDays = Duration.between(createdAt, now).toMillis() / 86400000d;
        if (ageDays <= 0d) {
            return 1d;
        }
        return Math.exp(-Math.log(2d) * ageDays / halfLifeDays);
    }

    /**
     * Chat recall entry with full observability facts. Exceptions are degraded to ERROR traces so chat
     * sending can continue with the original message.
     *
     * @param userMessage full user message used as the recall query
     * @param workingDir session working dir for detail materialization, nullable
     */
    public RecallTrace traceForChat(String userMessage, String workingDir) {
        long start = System.currentTimeMillis();
        if (userMessage == null) {
            return RecallTrace.skipped(null, null, "BLANK_QUERY", elapsedSince(start));
        }
        String query = userMessage.trim();
        if (query.isEmpty()) {
            return RecallTrace.skipped(query, userMessage, "BLANK_QUERY", elapsedSince(start));
        }
        if (query.startsWith("/")) {
            return RecallTrace.skipped(query, userMessage, "SLASH_COMMAND", elapsedSince(start));
        }
        try {
            RecallRun run = recallWithFilter(query, props.getRecall().getTopK(),
                    buildChatFilter(resolveMinTier(workingDir)));
            if (run.hits.isEmpty()) {
                return RecallTrace.noHit(query, userMessage, run.stats, elapsedSince(start));
            }
            List<RagChunk> hitChunks = run.chunks();
            String augmented = buildAugmentedMessage(query, hitChunks, workingDir);
            log.info("refinery-recall-triggered queryLen={} hits={} augmentedLen={}",
                    query.length(), run.hits.size(), augmented.length());
            return RecallTrace.hit(query, augmented, toScoredHits(run.hits), run.stats, elapsedSince(start));
        } catch (RuntimeException e) {
            log.warn("refinery-recall-failed reason={}", e.getMessage(), e);
            return RecallTrace.error(query, userMessage, e.getClass().getSimpleName(), e.getMessage(), elapsedSince(start));
        }
    }

    private long elapsedSince(long start) {
        return Math.max(0L, System.currentTimeMillis() - start);
    }

    private List<ScoredRecallHit> toScoredHits(List<Scored> scoredHits) {
        List<ScoredRecallHit> out = new ArrayList<>(scoredHits.size());
        for (int i = 0; i < scoredHits.size(); i++) {
            Scored scored = scoredHits.get(i);
            RagChunk c = scored.chunk;
            out.add(new ScoredRecallHit(
                    c.getId(),
                    i + 1,
                    c.getContent().getTitle(),
                    c.getContent().getConclusion(),
                    c.getSourceSessionId(),
                    c.getSourceMsgRange(),
                    scored.parts.finalScore,
                    scored.parts.vectorScore,
                    scored.parts.signalScore,
                    scored.parts.timeScore,
                    c.getEmbeddingModel(),
                    c.getSourceType().name(),
                    c.getTier().name(),
                    c.getEnv(),
                    c.getScore(),
                    c.getCreatedAt()));
        }
        return out;
    }

    /**
     * 指针注入契约: 只注入 title/特征/详情路径, 正文物化到 workingDir 下的 detail 文件.
     * 保留 {@code [历史参考]} 头与 {@code ---} 分隔, 前端召回卡片与消息透传逻辑不变.
     */
    private String buildAugmentedMessage(String query, List<RagChunk> hits, String workingDir) {
        List<String> detailPaths = detailStore.store(workingDir, hits);
        return "[历史参考]\n" + RecallPointerFormatter.format(hits, detailPaths) + "---\n" + query;
    }

    /**
     * chat 召回过滤: 来源过滤叠加 workspace 声明的可信度下限 (tier 门禁, 设计方案 §A2)。
     * {@code minTier=null} 保持现状 (EXPLORATORY 可入); 排障 workspace 声明 PENDING/VERIFIED 后,
     * 未验证 chunk 结构性出局——宁可召回空转, 不注入未验证结论。
     */
    private java.util.function.Predicate<RagChunk> buildChatFilter(TrustTier minTier) {
        java.util.function.Predicate<RagChunk> source;
        if (props.getRecall().isCrossSourceEnabled()) {
            source = c -> c.getSourceType() == SourceType.CHAT
                    || (c.getSourceType() == SourceType.DIAGNOSE && c.getTier() == TrustTier.VERIFIED);
        } else {
            source = c -> c.getSourceType() == SourceType.CHAT;
        }
        if (minTier == null) {
            return source;
        }
        return source.and(c -> c.getTier().ordinal() <= minTier.ordinal());
    }

    /** tier 分层余弦硬闸: 该 tier 未配置时回落全局 minVectorScore. */
    private double vectorFloor(RefineryProperties.Recall recall, TrustTier tier) {
        java.util.Map<TrustTier, Double> byTier = recall.getMinVectorScoreByTier();
        if (byTier != null) {
            Double floor = byTier.get(tier);
            if (floor != null) {
                return floor;
            }
        }
        return recall.getMinVectorScore();
    }

    /** 从 workingDir 的 workspace manifest 解析召回可信度下限; 失败静默回 null (不限制). */
    private TrustTier resolveMinTier(String workingDir) {
        if (workspaceResolver == null || workingDir == null || workingDir.trim().isEmpty()) {
            return null;
        }
        try {
            WorkspaceContext context = workspaceResolver.resolve(workingDir);
            return context == null ? null : context.getRecallMinTier();
        } catch (RuntimeException e) {
            log.warn("refinery-recall-min-tier-resolve-failed workingDir={} reason={}",
                    workingDir, e.getMessage());
            return null;
        }
    }

    private static final class Scored {
        final RagChunk chunk;
        final double score;
        final ScoreParts parts;

        Scored(RagChunk chunk, ScoreParts parts) {
            this.chunk = chunk;
            this.score = parts.finalScore;
            this.parts = parts;
        }
    }

    private static final class ScoreParts {
        final double finalScore;
        final double vectorScore;
        final double signalScore;
        final double timeScore;

        ScoreParts(double finalScore, double vectorScore, double signalScore, double timeScore) {
            this.finalScore = finalScore;
            this.vectorScore = vectorScore;
            this.signalScore = signalScore;
            this.timeScore = timeScore;
        }
    }

    private static final class RecallRun {
        final List<Scored> hits;
        final RecallStats stats;

        RecallRun(List<Scored> hits, RecallStats stats) {
            this.hits = hits;
            this.stats = stats;
        }

        List<RagChunk> chunks() {
            List<RagChunk> chunks = new ArrayList<>(hits.size());
            for (Scored hit : hits) {
                chunks.add(hit.chunk);
            }
            return chunks;
        }
    }
}
