package com.example.agentweb.infra.refinery.config;

import com.example.agentweb.domain.refinery.TrustTier;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Knowledge Refinery 子域配置, 通过 {@code agent.refinery.*} 绑定.
 *
 * <p>默认 {@code enabled=false} 是兜底: 缺 API key 时启动不挂.
 * 仅在显式打开时 ArkEmbeddingClient / ChatRefineryTrigger / /recall 拦截才生效.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
@Component
@ConfigurationProperties(prefix = "agent.refinery")
@Getter
@Setter
public class RefineryProperties {

    private boolean enabled = false;

    private Poll poll = new Poll();
    private Refine refine = new Refine();
    private Embedding embedding = new Embedding();
    private Privacy privacy = new Privacy();
    private Recall recall = new Recall();
    private Ttl ttl = new Ttl();

    /** Scheduler 轮询参数. */
    @Getter
    @Setter
    public static class Poll {
        private int silentMinutes = 30;
        private int intervalSeconds = 300;
        private int maxPerTick = 5;
        private int maxRetries = 3;
    }

    /** LLM 评分 + 结论压缩参数. */
    @Getter
    @Setter
    public static class Refine {
        private boolean enabled = true;
        private int timeoutSeconds = 180;
        private String promptTemplate = "classpath:/refinery-refine-prompt.md";
        private int tokenBudget = 30000;
        private double scoreThreshold = 0.5;
        /** below-threshold 会话是否落 chat_rag_discarded 留痕, 供管理台"已丢弃(低分)"展示与阈值校准. */
        private boolean persistDiscarded = true;
        /** 评分专用模型, 空 = 用 session CLI 默认模型 (与主对话一致). 高频评分可指定廉价模型如 claude-haiku-4-5-20251001. */
        private String model = "";
        private Retry retry = new Retry();
    }

    /**
     * 单次 refine 内部的<b>即时指数退避重试</b>, 专扛 CLI 后端中转的瞬态 5xx
     * (如 503 Service Unavailable / overloaded / 429). 与 {@link Poll#getMaxRetries()}
     * 正交: 后者是<b>跨 tick 的会话级</b>重试 (分钟级), 本配置是<b>一次评分内</b>的秒级重试,
     * 两者叠加。只对可识别的瞬态错误重试; 鉴权失败 / 模型不支持 / 超时 / 进程启动失败等
     * 确定性错误不重试, 立即上抛。
     */
    @Getter
    @Setter
    public static class Retry {
        /** 总尝试次数 (含首次). 1 = 关闭重试, 行为与历史一致. */
        private int maxAttempts = 3;
        /** 首次退避毫秒数. */
        private long initialBackoffMs = 1000L;
        /** 退避倍率, 每次重试 backoff *= multiplier. */
        private double multiplier = 2.0D;
        /** 退避上限毫秒数, 封顶避免单次评分拖太久 (上游 fixedDelay 调度不会堆积). */
        private long maxBackoffMs = 8000L;
    }

    /** Ark embedding HTTP 客户端参数. API key 必须经环境变量传入, 严禁硬编码. */
    @Getter
    @Setter
    public static class Embedding {
        private String endpoint = "https://ark.cn-beijing.volces.com/api/coding/v3";
        private String model = "doubao-embedding-vision";
        private String apiKey = "";
        private int dimension = 2048;
        private int maxInputChars = 8000;
        private int httpConnectTimeoutMs = 5000;
        private int httpReadTimeoutMs = 30000;
    }

    /** 入库前对会话文本的脱敏正则. 默认覆盖 API key / JWT / Windows 用户名路径段. */
    @Getter
    @Setter
    public static class Privacy {
        private List<String> redactPatterns = new ArrayList<>(Arrays.asList(
                "(?i)(api[_-]?key|secret|token|password)[\"'\\s:=]+[A-Za-z0-9\\-_./+=]{8,}",
                "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+",
                "(?i)C:\\\\Users\\\\[^\\\\\\s]+",
                "(?i)/(?:home|Users)/[^/\\s]+"
        ));
    }

    /** 召回检索 + 重排参数. 是否触发召回由前端 "RAG 召回" 开关逐条决定, 此处不再有服务端开关. */
    @Getter
    @Setter
    public static class Recall {
        private int topK = 3;
        private boolean includeArchived = false;
        private boolean crossSourceEnabled = false;
        private double halfLifeDays = 30d;
        /**
         * 最终融合分的<b>绝对</b>下限. {@code <=0} 关闭. 命中分低于此值的 chunk 不召回,
         * 防止候选池稀疏时用低相关结果凑满 topK. 阈值与 embedding 标度相关, 建议看
         * {@code chat-rag-recall} debug 日志里的 topScore 标定。
         */
        private double minScore = 0d;
        /**
         * 最终融合分相对<b>最高命中</b>的下限比例, 取值 (0,1]. {@code <=0} 关闭. 只保留
         * {@code score >= topScore * minScoreRatio} 的 chunk——与 embedding 绝对标度无关,
         * 语义是"只留与最佳命中明显同档的结果", 长尾弱相关被截掉。与 {@link #minScore} 叠加, 任一不满足即截断。
         */
        private double minScoreRatio = 0d;
        /**
         * <b>余弦相似度</b>(纯向量, 非融合分)的绝对硬闸, 取值 (0,1]. {@code <=0} 关闭.
         * 在融合排序<b>之前</b>把 {@code cosine < minVectorScore} 的 chunk 直接剔除, 不进候选池。
         *
         * <p>这是真正的"相关性闸": 与 {@link #minScore}/{@link #minScoreRatio} 的本质区别在于,
         * 融合分含 {@code γ·decay·score} 这一<b>与 query 无关</b>的常数底 (每条都垫 ~0.05-0.1),
         * 既抬高 topScore 又污染绝对/相对阈值; 余弦是未被污染的语义相关度信号。相对闸只能砍"比最佳明显差"
         * 的尾巴, 无法拒绝"最佳命中本身就是噪声"——余弦硬闸正面解决后者。阈值可看 {@code chat-rag-recall}
         * debug 日志里的 topCosine 校准: 偏多调高(0.55/0.6), 误杀有用结果调低(0.45)。</p>
         */
        private double minVectorScore = 0d;

        /**
         * 按 tier 分层的余弦硬闸 (双轴阈值, 设计方案 §A2): 低可信要求更高相关性。
         * 未配置的 tier 回落 {@link #minVectorScore}。换 embedding 模型须整组重标定。
         */
        private java.util.Map<TrustTier, Double> minVectorScoreByTier =
                new java.util.EnumMap<TrustTier, Double>(TrustTier.class);

        /** 进程内快照缓存开关 (设计方案 §B4): 关闭后每次召回直查 SQLite. */
        private boolean cacheEnabled = true;

        /** 快照软上限: 活跃 chunk 超过此数不缓存并告警回落直查, 防 heap 意外膨胀; <=0 不设限. */
        private int cacheMaxChunks = 50000;
        private Ranking ranking = new Ranking();

        /** 重排三维权重 (α 向量 + β 关键词 + γ 时间衰减). 约束 α+β+γ≈1, 不强校验. */
        @Getter
        @Setter
        public static class Ranking {
            private double vectorWeight = 0.7d;
            private double signalWeight = 0.2d;
            // γ: 入库质量分(score)在召回端唯一的生效通道, 被 decay∈[0,1] 二次衰减后再乘 γ.
            // 0.1 时本项最大贡献仅 0.1, cosine 独占 0.7, 入库 score 几乎不影响排序——对 chat 这是
            // 覆盖全量、不依赖人工反馈的主质量信号, 故默认 0.2 让它有感(与 application.yml 保持一致).
            private double timeDecayWeight = 0.2d;
        }
    }

    /** 按 TTL 分类的过期天数. 由 refine 阶段 LLM 给出分类, 入库时换算 expires_at. */
    @Getter
    @Setter
    public static class Ttl {
        private int codeDays = 14;
        private int deployDays = 30;
        private int businessDays = 60;
        private int generalDays = 30;
    }
}
