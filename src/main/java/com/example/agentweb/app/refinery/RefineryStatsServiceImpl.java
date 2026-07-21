package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-03
 */
@Service
@ConditionalOnProperty(prefix = "agent.refinery", name = "enabled", havingValue = "true")
public class RefineryStatsServiceImpl implements RefineryStatsService {

    private final RagChunkRepository chunkRepo;

    public RefineryStatsServiceImpl(RagChunkRepository chunkRepo) {
        this.chunkRepo = chunkRepo;
    }

    @Override
    public RefineryStats getStats() {
        return new RefineryStats(buildChunkStats());
    }

    private RefineryStats.ChunkStats buildChunkStats() {
        Map<SourceType, Integer> bySource = chunkRepo.countActiveBySourceType();
        Map<TrustTier, Integer> byTier = chunkRepo.countActiveByTier();
        int totalActive = bySource.values().stream().mapToInt(Integer::intValue).sum();
        int totalArchived = chunkRepo.countArchived();
        return new RefineryStats.ChunkStats(totalActive, totalArchived, bySource, byTier);
    }
}
