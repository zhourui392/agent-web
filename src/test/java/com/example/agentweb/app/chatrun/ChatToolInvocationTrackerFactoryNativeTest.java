package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ToolInvocation;
import com.example.agentweb.domain.chatrun.ToolInvocationRepository;
import com.example.agentweb.domain.chatrun.ToolInvocationStatus;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.cli.JsonToolInvocationEventExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifies the NATIVE stream-to-tool-invocation persistence boundary.
 *
 * @author alex
 * @since 2026-07-30
 */
class ChatToolInvocationTrackerFactoryNativeTest {

    @Test
    void persistsNativeInputWhenAdjacentEventsArriveOnDifferentThreads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ToolInvocationRepository repository = mock(ToolInvocationRepository.class);
        ChatToolInvocationTrackerFactory factory = new ChatToolInvocationTrackerFactory(
                repository, new JsonToolInvocationEventExtractor(mapper), mapper,
                true, 65_536, 65_536);
        ChatToolInvocationTrackerFactory.Tracker tracker =
                factory.open("session-1", "run-1", AgentType.NATIVE);

        onFreshThread(() -> tracker.accept("""
                {"type":"stream_event","event":{"type":"content_block_start",
                 "content_block":{"type":"tool_use","id":"call-1","name":"LogQuery"}}}
                """));
        onFreshThread(() -> tracker.accept("""
                {"type":"stream_event","event":{"type":"content_block_delta",
                 "delta":{"type":"input_json_delta","tool_use_id":"call-1",
                 "partial_json":"{\\\"environment\\\":\\\"test\\\",\\\"service\\\":\\\"agent-web\\\"}"}}}
                """));
        onFreshThread(() -> tracker.accept("""
                {"type":"user","message":{"content":[{"type":"tool_result",
                 "tool_use_id":"call-1","content":"matched","is_error":false}]}}
                """));

        ArgumentCaptor<ToolInvocation> saved = ArgumentCaptor.forClass(ToolInvocation.class);
        verify(repository, times(2)).save(saved.capture());
        List<ToolInvocation> invocations = saved.getAllValues();
        assertThat(invocations.get(0).getInputJson()).isEqualTo("{}");
        assertThat(invocations.get(1).getInputJson())
                .isEqualTo("{\"environment\":\"test\",\"service\":\"agent-web\"}");
        assertThat(invocations.get(1).getProviderCallId()).isEqualTo("call-1");
        assertThat(invocations.get(1).getStatus()).isEqualTo(ToolInvocationStatus.SUCCEEDED);
    }

    private static void onFreshThread(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError("fresh-thread action failed", failure.get());
        }
    }
}
