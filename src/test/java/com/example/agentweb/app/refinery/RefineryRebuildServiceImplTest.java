package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.SessionRefineryStateRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RefineryRebuildServiceImpl} 应用层单测.
 *
 * <p>mock 仓库 + mock {@link RefineryAppService}; executor 用直跑 {@code Runnable::run}
 * 让异步重跑在当前线程同步完成, 便于断言。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-31
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RefineryRebuildServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-05-31T10:00:00Z");
    private static final long SECONDS_PER_DAY = 86400L;

    @Mock private SessionRepository sessionRepo;
    @Mock private RagChunkRepository chunkRepo;
    @Mock private SessionRefineryStateRepository stateRepo;
    @Mock private RefineryAppService appService;

    private final Executor directExecutor = Runnable::run;
    private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

    private RefineryRebuildServiceImpl service;

    @BeforeEach
    public void setUp() {
        service = new RefineryRebuildServiceImpl(
                sessionRepo, chunkRepo, stateRepo, appService, directExecutor, fixedClock);
    }

    @Test
    public void rebuildRecent_queries_sessions_by_afterMs_clears_chunk_and_state_then_reruns_each_session() {
        when(sessionRepo.findIdsWithLastMessageAfter(NOW.minusSeconds(7 * SECONDS_PER_DAY).toEpochMilli()))
                .thenReturn(Arrays.asList("s1", "s2"));
        when(chunkRepo.deleteBySourceSessionId("s1")).thenReturn(2);
        when(chunkRepo.deleteBySourceSessionId("s2")).thenReturn(3);

        RebuildResult result = service.rebuildRecent(7);

        // 清理: 每个会话删 chunk + 删 state
        verify(chunkRepo).deleteBySourceSessionId("s1");
        verify(chunkRepo).deleteBySourceSessionId("s2");
        verify(stateRepo).deleteBySessionId("s1");
        verify(stateRepo).deleteBySessionId("s2");
        // 重跑: 直跑 executor 下每个会话各调一次
        verify(appService).refineAndIngest("s1");
        verify(appService).refineAndIngest("s2");

        assertTrue(result.isStarted());
        assertEquals(7, result.getDays());
        assertEquals(2, result.getMatchedSessions());
        assertEquals(5, result.getChunksDeleted());
        assertEquals(2, result.getQueued());
    }

    @Test
    public void rebuildRecent_no_matching_sessions_should_be_started_with_zero_counts_no_repo_writes() {
        when(sessionRepo.findIdsWithLastMessageAfter(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Collections.emptyList());

        RebuildResult result = service.rebuildRecent(3);

        assertTrue(result.isStarted());
        assertEquals(0, result.getMatchedSessions());
        assertEquals(0, result.getChunksDeleted());
        verify(appService, never()).refineAndIngest(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void rebuildRecent_single_session_refine_throws_does_not_affect_others_still_started() {
        when(sessionRepo.findIdsWithLastMessageAfter(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Arrays.asList("bad", "good"));
        when(appService.refineAndIngest("bad")).thenThrow(new RuntimeException("boom"));
        when(appService.refineAndIngest("good")).thenReturn(true);

        RebuildResult result = service.rebuildRecent(7);

        assertTrue(result.isStarted());
        verify(appService).refineAndIngest("bad");
        verify(appService).refineAndIngest("good");
    }

    @Test
    public void rebuildRecent_rebuild_already_in_progress_returns_busy_no_duplicate_cleanup_or_submission() throws Exception {
        // 用阻塞 executor 模拟"上一轮还在后台跑", 占住护栏
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean firstTaskRan = new AtomicBoolean(false);
        Executor blockingExecutor = task -> {
            Thread t = new Thread(() -> {
                firstTaskRan.set(true);
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                task.run();
            });
            t.setDaemon(true);
            t.start();
        };
        RefineryRebuildServiceImpl blocking = new RefineryRebuildServiceImpl(
                sessionRepo, chunkRepo, stateRepo, appService, blockingExecutor, fixedClock);
        when(sessionRepo.findIdsWithLastMessageAfter(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Arrays.asList("s1"));
        when(chunkRepo.deleteBySourceSessionId("s1")).thenReturn(1);

        RebuildResult first = blocking.rebuildRecent(7);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(first.isStarted());

        // 护栏被占, 第二次应 busy: 不再清理 (deleteBySourceSessionId 仍只被第一次调过 1 次)
        RebuildResult second = blocking.rebuildRecent(7);
        assertFalse(second.isStarted());
        assertEquals("rebuild-in-progress", second.getReason());
        assertEquals(0, second.getChunksDeleted());
        assertEquals(0, second.getQueued());

        release.countDown();
        verify(chunkRepo).deleteBySourceSessionId("s1"); // 仅第一次清理过一次
    }
}
