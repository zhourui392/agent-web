package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Marks database-active runs as interrupted when the current JVM cannot own their process.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Service
@Slf4j
public class ChatRunRecoveryService {

    private static final String RESTART_MESSAGE = "服务重启，任务已中断";

    private final ChatRunQueryService queryService;
    private final ChatRunLifecycleService lifecycleService;

    public ChatRunRecoveryService(ChatRunQueryService queryService,
                                  ChatRunLifecycleService lifecycleService) {
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
    }

    public int interruptOrphans() {
        int recovered = 0;
        for (String runId : queryService.findActiveRunIds()) {
            try {
                lifecycleService.interrupt(ChatRunId.of(runId), RESTART_MESSAGE);
                recovered++;
            } catch (RuntimeException ex) {
                log.error("chat-run-orphan-recovery-failed runId={}", runId, ex);
            }
        }
        return recovered;
    }
}
