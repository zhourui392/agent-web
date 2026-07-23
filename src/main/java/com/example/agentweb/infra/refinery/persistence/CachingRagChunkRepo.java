package com.example.agentweb.infra.refinery.persistence;

import com.example.agentweb.domain.refinery.ArchiveReason;
import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.config.refinery.RefineryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link RagChunkRepository} 的进程内快照缓存装饰器 (设计方案 §B4).
 *
 * <p>只加速召回读路径 {@link #findActive}: 快照持有全量活跃 chunk (含解码 embedding),
 * "未过期"谓词在读取时内存过滤——TTL 自然到期无需失效; 任一写路径置空快照, 下次读全量重载
 * (单写者进程, 万条量级重载 <100ms, 不做增量维护). admin 分页/统计等低频读保持直查.</p>
 *
 * <p>护栏: {@code agent.refinery.recall.cache-enabled} 关闭或活跃池超
 * {@code cache-max-chunks} 软上限时自动回落直查, 防 heap 意外膨胀.
 * 快照返回共享实例, 调用方不得原地修改聚合状态.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
@Repository
@Primary
@Slf4j
public class CachingRagChunkRepo implements RagChunkRepository {

    private final SqliteRagChunkRepo delegate;
    private final RefineryProperties props;
    private volatile List<RagChunk> snapshot;

    public CachingRagChunkRepo(SqliteRagChunkRepo delegate, RefineryProperties props) {
        this.delegate = delegate;
        this.props = props;
    }

    @Override
    public List<RagChunk> findActive(Instant now) {
        if (!props.getRecall().isCacheEnabled()) {
            return delegate.findActive(now);
        }
        List<RagChunk> cached = snapshot;
        if (cached == null) {
            cached = reload(now);
            if (cached == null) {
                return delegate.findActive(now);
            }
        }
        return filterUnexpired(cached, now);
    }

    /** 全量重载快照; 超软上限时返回 null 表示回落直查 (不缓存). */
    private List<RagChunk> reload(Instant now) {
        int maxChunks = props.getRecall().getCacheMaxChunks();
        List<RagChunk> loaded = maxChunks > 0
                ? delegate.findActiveLimited(now, maxChunks + 1)
                : delegate.findActive(now);
        if (maxChunks > 0 && loaded.size() > maxChunks) {
            log.warn("rag-chunk-cache-bypass activeCount={} exceeds cacheMaxChunks={}, fallback to direct query",
                    loaded.size(), maxChunks);
            return null;
        }
        snapshot = loaded;
        if (log.isDebugEnabled() && !loaded.isEmpty()) {
            long embeddingBytes = (long) loaded.size() * loaded.get(0).getEmbedding().length * 4L;
            log.debug("rag-chunk-cache-reloaded count={} estEmbeddingMB={}",
                    loaded.size(), embeddingBytes / 1024 / 1024);
        }
        return loaded;
    }

    private List<RagChunk> filterUnexpired(List<RagChunk> chunks, Instant now) {
        List<RagChunk> result = new ArrayList<RagChunk>(chunks.size());
        for (RagChunk chunk : chunks) {
            if (chunk.getExpiresAt() == null || chunk.getExpiresAt().isAfter(now)) {
                result.add(chunk);
            }
        }
        return result;
    }

    private void invalidate() {
        snapshot = null;
    }

    @Override
    public void save(RagChunk chunk) {
        delegate.save(chunk);
        invalidate();
    }

    @Override
    public boolean markArchived(String id, Instant when, ArchiveReason reason) {
        boolean changed = delegate.markArchived(id, when, reason);
        invalidate();
        return changed;
    }

    @Override
    public int archiveExpiredBefore(Instant cutoff) {
        int rows = delegate.archiveExpiredBefore(cutoff);
        invalidate();
        return rows;
    }

    @Override
    public boolean updateTier(String chunkId, TrustTier tier) {
        boolean changed = delegate.updateTier(chunkId, tier);
        invalidate();
        return changed;
    }

    @Override
    public int deleteBySourceSessionId(String sessionId) {
        int rows = delegate.deleteBySourceSessionId(sessionId);
        invalidate();
        return rows;
    }

    @Override
    public boolean deleteById(String id) {
        boolean deleted = delegate.deleteById(id);
        invalidate();
        return deleted;
    }

    @Override
    public Optional<RagChunk> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public Map<SourceType, Integer> countActiveBySourceType() {
        return delegate.countActiveBySourceType();
    }

    @Override
    public Map<TrustTier, Integer> countActiveByTier() {
        return delegate.countActiveByTier();
    }

    @Override
    public int countArchived() {
        return delegate.countArchived();
    }

    /** 遥测计数不进召回排序，不失效快照。 */
    @Override
    public int incrementInjectCount(List<String> chunkIds) {
        return delegate.incrementInjectCount(chunkIds);
    }

    @Override
    public boolean incrementAdoptCount(String chunkId) {
        return delegate.incrementAdoptCount(chunkId);
    }

    @Override
    public boolean updateEmbedding(String chunkId, float[] embedding, String embeddingModel) {
        boolean changed = delegate.updateEmbedding(chunkId, embedding, embeddingModel);
        invalidate();
        return changed;
    }
}
