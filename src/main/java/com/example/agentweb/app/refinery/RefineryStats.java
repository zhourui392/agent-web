package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;

import java.util.Map;

/**
 * Knowledge Refinery 统计指标 DTO, 供管理后台展示.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-03
 */
public final class RefineryStats {

    private final ChunkStats chunks;

    public RefineryStats(ChunkStats chunks) {
        this.chunks = chunks;
    }

    public ChunkStats getChunks() {
        return chunks;
    }

    public static final class ChunkStats {
        private final int totalActive;
        private final int totalArchived;
        private final Map<SourceType, Integer> bySourceType;
        private final Map<TrustTier, Integer> byTier;

        public ChunkStats(int totalActive, int totalArchived,
                          Map<SourceType, Integer> bySourceType,
                          Map<TrustTier, Integer> byTier) {
            this.totalActive = totalActive;
            this.totalArchived = totalArchived;
            this.bySourceType = bySourceType;
            this.byTier = byTier;
        }

        public int getTotalActive() { return totalActive; }
        public int getTotalArchived() { return totalArchived; }
        public Map<SourceType, Integer> getBySourceType() { return bySourceType; }
        public Map<TrustTier, Integer> getByTier() { return byTier; }
    }
}
