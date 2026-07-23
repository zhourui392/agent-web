package com.example.agentweb.infra.refinery.scheduling;

import com.example.agentweb.app.refinery.RefineryAppService;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.domain.refinery.SessionRefineryState;
import com.example.agentweb.config.refinery.RefineryProperties;
import com.example.agentweb.infra.log.MdcContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * refinery 定时调度器. {@code agent.refinery.enabled=true} 时启动注册 fixedDelay,
 * 每轮 tick 拉取 last_message_at &lt; now - silentMinutes 的会话, 串行交给 AppService 评分.
 *
 * <p>tick 内每个 sessionId 独立 try-catch, 任意异常不会让 scheduler 死掉.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
@Component
@ConditionalOnProperty(prefix = "agent.refinery", name = "enabled", havingValue = "true")
@Slf4j
public class ChatRefineryTrigger {

    private static final long SECONDS_PER_MINUTE = 60L;

    private final RefineryAppService appService;
    private final SessionRepository sessionRepo;
    private final RagChunkRepository chunkRepo;
    private final RefineryProperties props;
    private final TaskScheduler taskScheduler;
    private final Clock clock;

    public ChatRefineryTrigger(RefineryAppService appService,
                            SessionRepository sessionRepo,
                            RagChunkRepository chunkRepo,
                            RefineryProperties props,
                            TaskScheduler taskScheduler,
                            @Qualifier("chatRagClock") Clock clock) {
        this.appService = appService;
        this.sessionRepo = sessionRepo;
        this.chunkRepo = chunkRepo;
        this.props = props;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        if (taskScheduler == null) {
            log.info("refinery-scheduler-skip-register reason=task-scheduler-null");
            return;
        }
        Duration delay = Duration.ofSeconds(props.getPoll().getIntervalSeconds());
        taskScheduler.scheduleWithFixedDelay(this::tick, delay);
        log.info("refinery-scheduler-started intervalSec={} silentMin={} maxPerTick={}",
                props.getPoll().getIntervalSeconds(),
                props.getPoll().getSilentMinutes(),
                props.getPoll().getMaxPerTick());
    }

    /** 单轮调度. 测试可直接调用避免 TaskScheduler 真实计时. */
    public void tick() {
        String traceId = MdcContext.newTraceIdIfAbsent();
        long tickStartMs = System.currentTimeMillis();
        List<String> candidates;
        try {
            candidates = fetchCandidates();
        } catch (RuntimeException e) {
            log.error("refinery-tick-fetch-failed traceId={}", traceId, e);
            MdcContext.clear();
            return;
        }
        int saved = 0;
        for (String sessionId : candidates) {
            try {
                if (appService.refineAndIngest(sessionId)) {
                    saved++;
                }
            } catch (RuntimeException e) {
                log.error("refinery-tick-session-failed sessionId={} traceId={}",
                        sessionId, traceId, e);
            }
        }
        try {
            chunkRepo.archiveExpiredBefore(clock.instant());
        } catch (RuntimeException e) {
            log.error("refinery-tick-archive-failed traceId={}", traceId, e);
        }
        log.debug("refinery-tick-done candidateCount={} saved={} elapsedMs={}",
                candidates.size(), saved, System.currentTimeMillis() - tickStartMs);
        MdcContext.clear();
    }

    private List<String> fetchCandidates() {
        Instant cutoff = clock.instant()
                .minusSeconds((long) props.getPoll().getSilentMinutes() * SECONDS_PER_MINUTE);
        return sessionRepo.findIdsWithLastMessageBefore(
                cutoff.toEpochMilli(),
                SessionRefineryState.LAST_ERROR_BELOW_THRESHOLD,
                props.getPoll().getMaxRetries(),
                props.getPoll().getMaxPerTick());
    }
}
