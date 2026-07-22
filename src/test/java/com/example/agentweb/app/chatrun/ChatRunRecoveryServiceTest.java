package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ChatRunRecoveryServiceTest {

    @Test
    void recover_should_interrupt_every_orphan_and_continue_after_one_failure() {
        ChatRunQueryService queryService = mock(ChatRunQueryService.class);
        ChatRunLifecycleService lifecycleService = mock(ChatRunLifecycleService.class);
        when(queryService.findActiveRunIds()).thenReturn(Arrays.asList("run-1", "run-2", "run-3"));
        doThrow(new IllegalStateException("stale"))
                .when(lifecycleService).interrupt(ChatRunId.of("run-2"), "服务重启，任务已中断");
        ChatRunRecoveryService service = new ChatRunRecoveryService(queryService, lifecycleService);

        int recovered = service.interruptOrphans();

        assertEquals(2, recovered);
        org.mockito.InOrder order = inOrder(lifecycleService);
        order.verify(lifecycleService).interrupt(ChatRunId.of("run-1"), "服务重启，任务已中断");
        order.verify(lifecycleService).interrupt(ChatRunId.of("run-2"), "服务重启，任务已中断");
        order.verify(lifecycleService).interrupt(ChatRunId.of("run-3"), "服务重启，任务已中断");
    }
}
