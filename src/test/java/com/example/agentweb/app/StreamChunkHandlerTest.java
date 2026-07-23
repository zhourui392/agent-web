package com.example.agentweb.app;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StreamChunkHandler 单元测试：验证 resumeId 抽取已委托给 {@link AgentGateway#extractResumeId(AgentType, String)},
 * 不再依赖硬编码的 {@code "session_id"} 字符串嗅探。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
class StreamChunkHandlerTest {

    private static final int TOOL_RESULT_CONTENT_LIMIT = 12_000;

    private SessionRepository repo;
    private AgentGateway gateway;
    private StreamChunkHandler handler;

    @BeforeEach
    void setUp() {
        repo = mock(SessionRepository.class);
        gateway = mock(AgentGateway.class);
        handler = new StreamChunkHandler(repo, "sess-1", gateway, AgentType.CODEX);
    }

    @Test
    void onChunk_shouldDelegateExtractResumeIdToGateway() {
        when(gateway.extractResumeId(AgentType.CODEX, "any-chunk")).thenReturn("th-123");

        handler.onChunk(null).accept("any-chunk");

        verify(gateway).extractResumeId(AgentType.CODEX, "any-chunk");
        verify(repo).updateResumeId("sess-1", "th-123");
    }

    @Test
    void onChunk_shouldPersistResumeIdOnlyOnce() {
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn("th-1");

        handler.onChunk(null).accept("first");
        handler.onChunk(null).accept("second");

        verify(repo, times(1)).updateResumeId("sess-1", "th-1");
    }

    @Test
    void onChunk_shouldSkipPersistWhenExtractorReturnsNull() {
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);

        handler.onChunk(null).accept("any line");

        verify(repo, never()).updateResumeId(anyString(), anyString());
    }

    @Test
    void onChunk_shouldForwardNormalizedChunkToAdditionalAction() {
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);
        when(gateway.normalizeChunk(AgentType.CODEX, "raw chunk"))
                .thenReturn(Collections.singletonList("normalized chunk"));
        AtomicReference<String> captured = new AtomicReference<String>();

        handler.onChunk(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String chunk) {
                captured.set(chunk);
            }
        }).accept("raw chunk");

        assertEquals("normalized chunk", captured.get());
    }

    @Test
    void onChunk_normalizerEmitsMultipleLines_shouldForwardEach() {
        // 工具调用启动场景: 单条原始事件 → 2 条前端事件 (tool_use start + input_json_delta)
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);
        when(gateway.normalizeChunk(AgentType.CODEX, "raw"))
                .thenReturn(Arrays.asList("first", "second"));
        final java.util.List<String> captured = new java.util.ArrayList<String>();

        handler.onChunk(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String chunk) {
                captured.add(chunk);
            }
        }).accept("raw");

        assertEquals(Arrays.asList("first", "second"), captured);
    }

    @Test
    void onChunk_largeToolResult_shouldTruncateBeforeForwardingAndPersisting() {
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);
        String output = repeat("x", TOOL_RESULT_CONTENT_LIMIT + 1_000);
        String normalized = "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"item_1\",\"content\":\"" + output + "\",\"is_error\":false}]}}";
        when(gateway.normalizeChunk(AgentType.CODEX, "raw")).thenReturn(Collections.singletonList(normalized));
        AtomicReference<String> forwarded = new AtomicReference<String>();

        handler.onChunk(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String chunk) {
                forwarded.set(chunk);
            }
        }).accept("raw");
        handler.onExit(null).accept(0);

        assertTrue(forwarded.get().length() < normalized.length(), "推给前端的工具输出应先截断");
        assertTrue(forwarded.get().contains("agent-web truncated tool output"));
        assertTrue(forwarded.get().contains("original " + output.length() + " chars"));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(repo).addMessage(eq("sess-1"), captor.capture());
        String persisted = captor.getValue().getContent();
        assertTrue(persisted.length() < normalized.length(), "落库的工具输出应先截断");
        assertTrue(persisted.contains("agent-web truncated tool output"));
    }

    @Test
    void onChunk_normalizerEmitsEmptyList_shouldSkipForwarding() {
        // 重连噪音 error 事件场景: 归一化返回空 list, 前端不应收到任何 chunk
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);
        when(gateway.normalizeChunk(AgentType.CODEX, "noise"))
                .thenReturn(Collections.<String>emptyList());
        AtomicInteger callCount = new AtomicInteger(0);

        handler.onChunk(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String chunk) {
                callCount.incrementAndGet();
            }
        }).accept("noise");

        assertEquals(0, callCount.get());
    }

    @Test
    void onChunk_extractResumeIdShouldUseRawChunk_notNormalized() {
        // dialect.extractResumeId 按"原始方言事件"设计，必须在归一化之前调用
        when(gateway.extractResumeId(AgentType.CODEX, "raw thread.started line")).thenReturn("th-99");
        when(gateway.normalizeChunk(AgentType.CODEX, "raw thread.started line"))
                .thenReturn(Collections.singletonList("normalized-something"));

        handler.onChunk(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String chunk) { /* no-op */ }
        }).accept("raw thread.started line");

        verify(gateway).extractResumeId(AgentType.CODEX, "raw thread.started line");
        verify(repo).updateResumeId("sess-1", "th-99");
    }

    @Test
    void onExit_shouldPersistNormalizedAccumulation() {
        // 落库的是归一化后事件 (Claude 兼容契约), 前端 parseStreamJson 才能解析 codex 历史会话
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);
        when(gateway.normalizeChunk(AgentType.CODEX, "raw-1")).thenReturn(Collections.singletonList("norm-1"));
        when(gateway.normalizeChunk(AgentType.CODEX, "raw-2")).thenReturn(Collections.singletonList("norm-2"));

        handler.onChunk(null).accept("raw-1");
        handler.onChunk(null).accept("raw-2");
        handler.onExit(null).accept(0);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(repo).addMessage(eq("sess-1"), captor.capture());
        assertEquals("assistant", captor.getValue().getRole());
        assertTrue(captor.getValue().getContent().contains("norm-1"));
        assertTrue(captor.getValue().getContent().contains("norm-2"));
    }

    @Test
    void onChunk_shouldAccumulateNormalizedNotRaw() {
        // 关键回归: 累积归一化输出而非原始 codex 事件 —— 否则前端 parseStreamJson 解析空白
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);
        when(gateway.normalizeChunk(AgentType.CODEX, "{\"type\":\"item.completed\"}"))
                .thenReturn(Collections.singletonList("{\"type\":\"stream_event\"}"));

        handler.onChunk(null).accept("{\"type\":\"item.completed\"}");
        handler.onExit(null).accept(0);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(repo).addMessage(eq("sess-1"), captor.capture());
        String content = captor.getValue().getContent();
        assertTrue(content.contains("stream_event"), "应落库归一化事件");
        assertTrue(!content.contains("item.completed"), "不应落库原始 codex 事件");
    }

    @Test
    void onChunk_normalizerReturnsEmpty_shouldNotAccumulate() {
        // 重连噪音等归一化为空的事件不进累积, onExit 不应落库
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);
        when(gateway.normalizeChunk(AgentType.CODEX, "noise")).thenReturn(Collections.<String>emptyList());

        handler.onChunk(null).accept("noise");
        handler.onExit(null).accept(0);

        verify(repo, never()).addMessage(anyString(), any(ChatMessage.class));
    }

    @Test
    void onExit_emptyAccumulator_shouldNotPersistAssistantMessage() {
        handler.onExit(null).accept(0);

        verify(repo, never()).addMessage(anyString(), any(ChatMessage.class));
    }

    @Test
    void onExit_withRecallJson_shouldPersistViaReturningIdAndSaveRecall() {
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);
        when(gateway.normalizeChunk(AgentType.CODEX, "raw")).thenReturn(Collections.singletonList("norm"));
        when(repo.addMessageReturningId(eq("sess-1"), any(ChatMessage.class))).thenReturn(42L);

        handler.setRecallJson("{\"query\":\"q\",\"hits\":[]}");
        handler.onChunk(null).accept("raw");
        handler.onExit(null).accept(0);

        // 命中召回: 走带 id 的落库 + 写召回明细, 不走普通 addMessage
        verify(repo).addMessageReturningId(eq("sess-1"), any(ChatMessage.class));
        verify(repo).saveRecall(42L, "{\"query\":\"q\",\"hits\":[]}");
        verify(repo, never()).addMessage(anyString(), any(ChatMessage.class));
    }

    @Test
    void onExit_withoutRecallJson_shouldNotSaveRecall() {
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);
        when(gateway.normalizeChunk(AgentType.CODEX, "raw")).thenReturn(Collections.singletonList("norm"));

        handler.onChunk(null).accept("raw");
        handler.onExit(null).accept(0);

        verify(repo).addMessage(eq("sess-1"), any(ChatMessage.class));
        verify(repo, never()).saveRecall(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void onExit_withAssistantPersistedCallback_shouldPersistViaReturningIdEvenWithoutRecallJson() {
        when(gateway.extractResumeId(eq(AgentType.CODEX), anyString())).thenReturn(null);
        when(gateway.normalizeChunk(AgentType.CODEX, "raw")).thenReturn(Collections.singletonList("norm"));
        when(repo.addMessageReturningId(eq("sess-1"), any(ChatMessage.class))).thenReturn(77L);
        AtomicReference<Long> captured = new AtomicReference<Long>();

        handler.onAssistantPersisted(new java.util.function.LongConsumer() {
            @Override
            public void accept(long value) {
                captured.set(value);
            }
        });
        handler.onChunk(null).accept("raw");
        handler.onExit(null).accept(0);

        assertEquals(Long.valueOf(77L), captured.get());
        verify(repo).addMessageReturningId(eq("sess-1"), any(ChatMessage.class));
        verify(repo, never()).addMessage(anyString(), any(ChatMessage.class));
        verify(repo, never()).saveRecall(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void onExit_emptyAccumulator_shouldNotInvokeAssistantPersistedCallback() {
        AtomicInteger callCount = new AtomicInteger(0);

        handler.onAssistantPersisted(new java.util.function.LongConsumer() {
            @Override
            public void accept(long value) {
                callCount.incrementAndGet();
            }
        });
        handler.onExit(null).accept(0);

        assertEquals(0, callCount.get());
        verify(repo, never()).addMessageReturningId(anyString(), any(ChatMessage.class));
    }

    @Test
    void onExit_shouldForwardExitCodeToAdditionalAction() {
        AtomicInteger captured = new AtomicInteger(-99);

        handler.onExit(new java.util.function.IntConsumer() {
            @Override
            public void accept(int code) {
                captured.set(code);
            }
        }).accept(42);

        assertEquals(42, captured.get());
    }

    private String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }
}
