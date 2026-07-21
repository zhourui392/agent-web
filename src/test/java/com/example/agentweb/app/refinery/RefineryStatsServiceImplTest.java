package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-03
 */
public class RefineryStatsServiceImplTest {

    private RagChunkRepository chunkRepo;
    private RefineryStatsServiceImpl service;

    @BeforeEach
    public void setUp() {
        chunkRepo = mock(RagChunkRepository.class);
        service = new RefineryStatsServiceImpl(chunkRepo);
    }

    @Test
    public void get_stats_should_aggregate_chunk_metrics() {
        Map<SourceType, Integer> bySource = new EnumMap<>(SourceType.class);
        bySource.put(SourceType.CHAT, 10);
        bySource.put(SourceType.DIAGNOSE, 5);
        Map<TrustTier, Integer> byTier = new EnumMap<>(TrustTier.class);
        byTier.put(TrustTier.EXPLORATORY, 8);
        byTier.put(TrustTier.PENDING, 4);
        byTier.put(TrustTier.VERIFIED, 3);

        when(chunkRepo.countActiveBySourceType()).thenReturn(bySource);
        when(chunkRepo.countActiveByTier()).thenReturn(byTier);
        when(chunkRepo.countArchived()).thenReturn(7);

        RefineryStats stats = service.getStats();

        assertEquals(15, stats.getChunks().getTotalActive());
        assertEquals(7, stats.getChunks().getTotalArchived());
        assertEquals(10, stats.getChunks().getBySourceType().get(SourceType.CHAT));
        assertEquals(5, stats.getChunks().getBySourceType().get(SourceType.DIAGNOSE));
        assertEquals(8, stats.getChunks().getByTier().get(TrustTier.EXPLORATORY));
        assertEquals(4, stats.getChunks().getByTier().get(TrustTier.PENDING));
        assertEquals(3, stats.getChunks().getByTier().get(TrustTier.VERIFIED));
    }

    @Test
    public void get_stats_with_empty_repos_should_return_zeros() {
        when(chunkRepo.countActiveBySourceType()).thenReturn(new EnumMap<>(SourceType.class));
        when(chunkRepo.countActiveByTier()).thenReturn(new EnumMap<>(TrustTier.class));
        when(chunkRepo.countArchived()).thenReturn(0);

        RefineryStats stats = service.getStats();

        assertEquals(0, stats.getChunks().getTotalActive());
        assertEquals(0, stats.getChunks().getTotalArchived());
    }
}
