package com.example.agentweb.app.refinery;

import com.example.agentweb.app.logging.TraceContext;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.SessionRefineryStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link RefineryRebuildService} 默认实现.
 *
 * <p>语义: 先抢并发护栏 {@link #rebuildRunning}; 抢不到直接返回 busy (不做任何清理,
 * 避免"清了又没重跑"的空洞)。抢到后<b>同步</b>清空匹配会话的 chunk + state (几条 DELETE, 很快),
 * 再把重跑提交到单线程后台执行器串行跑 {@code refineAndIngest}, 跑完释放护栏。</p>
 *
 * <p>与 {@link RefineryAppService} 解耦: 后者只管单会话 ingest, 本类只管批量清理 + 调度。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-31
 */
@Service
@ConditionalOnProperty(prefix = "agent.refinery", name = "enabled", havingValue = "true")
@Slf4j
public class RefineryRebuildServiceImpl implements RefineryRebuildService {

    private static final long SECONDS_PER_DAY = 86400L;

    private final SessionRepository sessionRepo;
    private final RagChunkRepository chunkRepo;
    private final SessionRefineryStateRepository stateRepo;
    private final RefineryAppService appService;
    private final Executor rebuildExecutor;
    private final Clock clock;
    private final TraceContext traceContext;

    private final AtomicBoolean rebuildRunning = new AtomicBoolean(false);

    public RefineryRebuildServiceImpl(SessionRepository sessionRepo,
                                     RagChunkRepository chunkRepo,
                                     SessionRefineryStateRepository stateRepo,
                                     RefineryAppService appService,
                                     @Qualifier("chatRagRebuildExecutor") Executor rebuildExecutor,
                                     @Qualifier("chatRagClock") Clock clock,
                                     TraceContext traceContext) {
        this.sessionRepo = sessionRepo;
        this.chunkRepo = chunkRepo;
        this.stateRepo = stateRepo;
        this.appService = appService;
        this.rebuildExecutor = rebuildExecutor;
        this.clock = clock;
        this.traceContext = traceContext;
    }

    @Override
    public RebuildResult rebuildRecent(int days) {
        long afterMs = clock.instant().minusSeconds(days * SECONDS_PER_DAY).toEpochMilli();
        List<String> sessionIds = sessionRepo.findIdsWithLastMessageAfter(afterMs);

        if (!rebuildRunning.compareAndSet(false, true)) {
            log.info("refinery-rebuild-busy days={} matched={}", days, sessionIds.size());
            return RebuildResult.busy(days, sessionIds.size(), 0);
        }

        int chunksDeleted;
        try {
            chunksDeleted = clearRagData(sessionIds);
        } catch (RuntimeException e) {
            rebuildRunning.set(false);
            throw e;
        }
        log.info("refinery-rebuild-cleared days={} matched={} chunksDeleted={}",
                days, sessionIds.size(), chunksDeleted);

        try {
            rebuildExecutor.execute(() -> reingestAll(sessionIds));
        } catch (RuntimeException e) {
            rebuildRunning.set(false);
            throw e;
        }
        return RebuildResult.started(days, sessionIds.size(), chunksDeleted);
    }

    /** 同步硬删每个会话的 chunk + 幂等 state, 返回删除的 chunk 总行数. */
    private int clearRagData(List<String> sessionIds) {
        int chunksDeleted = 0;
        for (String sessionId : sessionIds) {
            chunksDeleted += chunkRepo.deleteBySourceSessionId(sessionId);
            stateRepo.deleteBySessionId(sessionId);
        }
        return chunksDeleted;
    }

    /** 后台串行重跑. 每个会话独立 try-catch, 单个失败不影响其余; 结束释放护栏. */
    private void reingestAll(List<String> sessionIds) {
        String traceId = traceContext.newTraceIdIfAbsent();
        long startMs = System.currentTimeMillis();
        int saved = 0;
        try {
            for (String sessionId : sessionIds) {
                try {
                    if (appService.refineAndIngest(sessionId)) {
                        saved++;
                    }
                } catch (RuntimeException e) {
                    log.error("refinery-rebuild-session-failed sessionId={} traceId={}",
                            sessionId, traceId, e);
                }
            }
        } finally {
            rebuildRunning.set(false);
            log.info("refinery-rebuild-done total={} saved={} elapsedMs={} traceId={}",
                    sessionIds.size(), saved, System.currentTimeMillis() - startMs, traceId);
            traceContext.clear();
        }
    }
}
