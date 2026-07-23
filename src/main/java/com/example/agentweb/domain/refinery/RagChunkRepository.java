package com.example.agentweb.domain.refinery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * chat_rag_chunk 聚合根仓库接口. 实现位于 infra.refinery.persistence.
 *
 * <p>读侧 (findActive) 直接返回聚合根列表是简化决定: 起步阶段 chunk 量 < 1 万, 全部反序列化为
 * 聚合根再做内存余弦扫描是可接受的; 数据规模上来后再拆 CQRS 读模型.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public interface RagChunkRepository {

    /** 新增一条 chunk; 主键冲突按 INSERT OR IGNORE 处理 (理论上 id 用 ULID/UUID 不会冲突). */
    void save(RagChunk chunk);

    /** 按 id 加载, 不存在返回 Optional.empty(). */
    Optional<RagChunk> findById(String id);

    /**
     * 召回候选: 返回所有未归档且未过期的 chunk 全集 (供内存余弦扫描).
     *
     * @param now 当前时刻, 用于过滤 expires_at < now 的记录
     * @return 活跃 chunk 列表; 调用方可能再做 archived_at / triggerSignals / score 加权排序
     */
    List<RagChunk> findActive(Instant now);

    /**
     * 软删一条 chunk (写入 archived_at). 用于 TTL 维护脚本或手工归档.
     *
     * @return true 命中并归档, false 未找到或已归档
     */
    boolean markArchived(String id, Instant when, ArchiveReason reason);

    /**
     * 批量软删: 把所有 expires_at &lt;= cutoff 且未归档的 chunk 标 archived_at.
     * 由 scheduler 维护任务定期调用, 控制活跃 chunk 行数.
     *
     * @param cutoff 时间分界点
     * @return 实际被标记的行数
     */
    int archiveExpiredBefore(Instant cutoff);

    /**
     * 硬删除某 session 名下全部 chunk (含已归档). 用于"清空并重跑"管理操作,
     * 区别于 {@link #markArchived}/{@link #archiveExpiredBefore} 的软删: 这里真正 DELETE,
     * 避免重跑后旧行持续堆积.
     *
     * @param sessionId 源会话 ID
     * @return 实际删除的行数
     */
    int deleteBySourceSessionId(String sessionId);

    /**
     * 升级 chunk 的 trust tier. 用于反馈→升级链路 (诊断 verdict=正确 → VERIFIED).
     * 仅写 tier 列, 不动其他字段; 不强校验方向 (域内 {@code RagChunk.upgradeTier} 已校验,
     * 此处只负责持久化).
     *
     * @return true 命中并更新, false 未找到
     */
    boolean updateTier(String chunkId, TrustTier tier);

    /** 统计活跃 chunk 按 source_type 分组的行数. */
    java.util.Map<SourceType, Integer> countActiveBySourceType();

    /** 统计活跃 chunk 按 tier 分组的行数. */
    java.util.Map<TrustTier, Integer> countActiveByTier();

    /** 统计已归档 chunk 总数. */
    int countArchived();

    /**
     * 硬删除单条 chunk. 供管理台"召回历史"逐条删除使用; 与 {@link #markArchived} 软删的区别:
     * 这里真正 DELETE, 列表里直接消失而非保留为"已归档"行。
     *
     * @param id chunk 主键
     * @return true 命中并删除, false 未找到
     */
    boolean deleteById(String id);

    /**
     * 注入遥测 (M4): 这批 chunk 被拼进某次 run/诊断的 prompt, inject_count 各自 +1.
     * 纯遥测列, 不进聚合状态; 失败由调用方旁路处理.
     *
     * @return 实际更新行数
     */
    int incrementInjectCount(List<String> chunkIds);

    /**
     * 采纳遥测 (M4): 反馈链路确认该 chunk 对结论有帮助 (tier 升 VERIFIED 同时机), adopt_count +1.
     *
     * @return true 命中并更新
     */
    boolean incrementAdoptCount(String chunkId);

    /**
     * 重嵌入迁移 (M4): 覆盖单条 chunk 的向量与模型标识. 用于 embed 文本构成变化
     * (如新增 triggerDescription) 后的存量渐进迁移, 只写 embedding 两列不动内容.
     *
     * @return true 命中并更新
     */
    boolean updateEmbedding(String chunkId, float[] embedding, String embeddingModel);
}
