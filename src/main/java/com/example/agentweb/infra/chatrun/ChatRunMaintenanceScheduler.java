package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ChatRunEventRetentionService;
import com.example.agentweb.app.chatrun.ChatRunRecoveryService;
import com.example.agentweb.app.chatrun.ChatRunRetentionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring lifecycle adapter for orphan recovery and bounded retention scans.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
@Slf4j
public class ChatRunMaintenanceScheduler {

    private final ChatRunRecoveryService recoveryService;
    private final ChatRunEventRetentionService retentionService;
    private final AtomicBoolean recovered = new AtomicBoolean(false);

    public ChatRunMaintenanceScheduler(ChatRunRecoveryService recoveryService,
                                       ChatRunEventRetentionService retentionService) {
        this.recoveryService = recoveryService;
        this.retentionService = retentionService;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void recoverOrphans() {
        if (!recovered.compareAndSet(false, true)) {
            return;
        }
        int count = recoveryService.interruptOrphans();
        if (count > 0) {
            log.warn("chat-run-orphan-recovered count={}", count);
        }
    }

    @Scheduled(fixedDelayString = "${agent.chat.resumable-stream.retention-scan-ms:3600000}")
    public void purgeExpired() {
        ChatRunRetentionResult result = retentionService.purgeExpired();
        if (result.getDeletedEvents() > 0 || result.getDeletedRuns() > 0) {
            log.info("chat-run-retention-purged events={} runs={}",
                    result.getDeletedEvents(), result.getDeletedRuns());
        }
    }
}
