package com.example.agentweb.app.requirement;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.domain.requirement.RequirementQuotaExceededException;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * run 发射器：配额闸、执行回调、SSE 转发、异常降级、计数回收。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementRunLauncherTest {

    private AgentGateway agentGateway;
    private RunEventBus eventBus;
    private StreamOutputExtractor outputExtractor;
    private RequirementProperties properties;
    private RequirementRunTracker tracker;
    private RequirementRunLauncher launcher;

    @BeforeEach
    public void setUp() {
        agentGateway = mock(AgentGateway.class);
        eventBus = mock(RunEventBus.class);
        outputExtractor = mock(StreamOutputExtractor.class);
        properties = new RequirementProperties();
        tracker = new RequirementRunTracker();
        launcher = new RequirementRunLauncher(agentGateway, eventBus, outputExtractor,
                properties, tracker, Runnable::run);
    }

    @Test
    public void launch_should_reject_when_run_quota_reached() {
        properties.getQuota().setMaxRunsPerRequirement(1);
        tracker.increment("R1");

        assertThrows(RequirementQuotaExceededException.class,
                () -> launcher.launch("R1", profile(), r -> { }));
    }

    @Test
    public void launch_should_stream_normalize_and_callback_with_plain_text() throws Exception {
        stubRunStream(List.of("line-1", "line-2"), 0);
        when(agentGateway.normalizeChunk(eq(AgentType.CLAUDE), anyString()))
                .thenAnswer(inv -> List.of((String) inv.getArgument(1)));
        when(outputExtractor.extractPlainText("line-1\nline-2\n")).thenReturn("计划正文");
        AtomicReference<RunResult> completed = new AtomicReference<>();

        launcher.launch("R1", profile(), completed::set);

        assertEquals(0, completed.get().getExitCode());
        assertEquals("计划正文", completed.get().getPlainText());
        verify(eventBus).publish("req-run-R1", 0, "chunk", "line-1");
        verify(eventBus).close("req-run-R1");
        assertEquals(0, tracker.activeCount("R1"));
    }

    @Test
    public void launch_should_swallow_gateway_failure_and_release_counter() throws Exception {
        doAnswer(inv -> {
            throw new java.io.IOException("spawn failed");
        }).when(agentGateway).runStream(any(), anyString(), anyString(), anyString(), isNull(),
                isNull(), anyLong(), any(), any(), isNull(), any());
        AtomicReference<RunResult> completed = new AtomicReference<>();

        launcher.launch("R1", profile(), completed::set);

        assertNull(completed.get());
        verify(eventBus).publish(eq("req-run-R1"), eq(0), eq("error"), anyString());
        verify(eventBus).close("req-run-R1");
        assertEquals(0, tracker.activeCount("R1"));
    }

    @Test
    public void launch_should_not_callback_on_nonzero_exit_but_still_report() throws Exception {
        stubRunStream(List.of(), 3);
        when(agentGateway.normalizeChunk(any(), anyString())).thenReturn(List.of());
        when(outputExtractor.extractPlainText(anyString())).thenReturn("");
        AtomicReference<RunResult> completed = new AtomicReference<>();

        launcher.launch("R1", profile(), completed::set);

        // 非 0 退出仍回调(由各 run 服务决定如何处置),发射器不吞
        assertEquals(3, completed.get().getExitCode());
        verify(outputExtractor).extractPlainText("");
    }

    @Test
    public void quota_precheck_should_not_touch_gateway() {
        properties.getQuota().setMaxRunsPerRequirement(1);
        tracker.increment("R1");

        assertThrows(RequirementQuotaExceededException.class, () -> launcher.assertRunQuota("R1"));
        verify(agentGateway, never()).normalizeChunk(any(), anyString());
    }

    @SuppressWarnings("unchecked")
    private void stubRunStream(List<String> chunks, int exitCode) throws Exception {
        doAnswer((Answer<Void>) inv -> {
            Consumer<String> onChunk = inv.getArgument(7);
            IntConsumer onExit = inv.getArgument(8);
            chunks.forEach(onChunk);
            onExit.accept(exitCode);
            return null;
        }).when(agentGateway).runStream(any(), anyString(), anyString(), anyString(), isNull(),
                isNull(), anyLong(), any(), any(), isNull(), any());
    }

    private RunProfile profile() {
        return new RunProfile("plan", AgentType.CLAUDE, "D:/tmp", "PROMPT", 60L, null);
    }
}
