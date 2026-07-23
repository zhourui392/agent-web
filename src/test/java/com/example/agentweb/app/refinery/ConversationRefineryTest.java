package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.refinery.ConversationTurn;
import com.example.agentweb.domain.refinery.ConversationView;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TtlCategory;
import com.example.agentweb.config.refinery.RefineryProperties;
import com.example.agentweb.app.agentrun.port.AgentCliInvoker;
import com.example.agentweb.app.agentrun.port.CliInvokeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
@ExtendWith(MockitoExtension.class)
public class ConversationRefineryTest {

    private static final String VALID_JSON = "{\n"
            + "  \"score\": 0.87,\n"
            + "  \"ttl_category\": \"deploy\",\n"
            + "  \"title\": \"SkipList 配置遗漏导致部署失败\",\n"
            + "  \"triggerSignals\": [\"启动报 SkipList\", \"deploy 卡住\"],\n"
            + "  \"context\": \"ctx text\",\n"
            + "  \"process\": \"1) a 2) b 3) c\",\n"
            + "  \"conclusion\": \"补齐 SkipList 配置\"\n"
            + "}";

    @Mock
    private AgentCliInvoker invoker;

    private RefineryProperties props;
    private ConversationRefinery refinery;

    @BeforeEach
    public void setUp() {
        props = new RefineryProperties();
        props.getRefine().setTimeoutSeconds(60);
        refinery = new ConversationRefinery(invoker, props,
                new com.example.agentweb.app.StreamOutputExtractor());
    }

    @Test
    public void refine_happy_parses_score_and_refined_content() throws Exception {
        ConversationView view = newView("user q", "agent a");
        when(invoker.invokeSync(eq(AgentType.CLAUDE), eq("/tmp"), anyString(), anyLong()))
                .thenReturn(VALID_JSON);

        RefineResult res = refinery.refine(view);

        assertEquals(0.87, res.getScore(), 1e-6);
        assertEquals(TtlCategory.DEPLOY, res.getTtlCategory());
        assertEquals("SkipList 配置遗漏导致部署失败", res.getContent().getTitle());
        assertEquals(Arrays.asList("启动报 SkipList", "deploy 卡住"),
                res.getContent().getTriggerSignals());
        assertEquals("补齐 SkipList 配置", res.getContent().getConclusion());
        // M4: 旧输出没有 triggerDescription 字段 → 归一化为空串, 解析不挂
        assertEquals("", res.getContent().getTriggerDescription());
    }

    @Test
    public void refine_should_parse_optional_trigger_description() throws Exception {
        ConversationView view = newView("user q", "agent a");
        String withDescription = VALID_JSON.replace("\"context\":",
                "\"triggerDescription\": \"部署卡在启动且日志报 SkipList 时\",\n  \"context\":");
        when(invoker.invokeSync(eq(AgentType.CLAUDE), eq("/tmp"), anyString(), anyLong()))
                .thenReturn(withDescription);

        RefineResult res = refinery.refine(view);

        assertEquals("部署卡在启动且日志报 SkipList 时", res.getContent().getTriggerDescription());
    }

    @Test
    public void refine_with_configured_model_uses_five_arg_invoke_to_pass_model() throws Exception {
        ConversationView view = newView("user q", "agent a");
        props.getRefine().setModel("claude-haiku-4-5-20251001");
        when(invoker.invokeSync(eq(AgentType.CLAUDE), eq("/tmp"), anyString(), anyLong(),
                eq("claude-haiku-4-5-20251001"))).thenReturn(VALID_JSON);

        RefineResult res = refinery.refine(view);

        assertEquals(0.87, res.getScore(), 1e-6);
        org.mockito.Mockito.verify(invoker).invokeSync(eq(AgentType.CLAUDE), eq("/tmp"),
                anyString(), anyLong(), eq("claude-haiku-4-5-20251001"));
        org.mockito.Mockito.verify(invoker, org.mockito.Mockito.never())
                .invokeSync(any(), anyString(), anyString(), anyLong());
    }

    @Test
    public void refine_without_configured_model_uses_four_arg_invoke() throws Exception {
        ConversationView view = newView("user q", "agent a");
        // props.getRefine().getModel() 默认 "" → 不透传 model
        when(invoker.invokeSync(eq(AgentType.CLAUDE), eq("/tmp"), anyString(), anyLong()))
                .thenReturn(VALID_JSON);

        refinery.refine(view);

        org.mockito.Mockito.verify(invoker).invokeSync(eq(AgentType.CLAUDE), eq("/tmp"),
                anyString(), anyLong());
        org.mockito.Mockito.verify(invoker, org.mockito.Mockito.never())
                .invokeSync(any(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    public void refine_oversized_messages_keep_recent_within_token_budget() throws Exception {
        List<ConversationTurn> turns = new ArrayList<>();
        turns.add(turn("user", repeat("OLDEST-", 200)));
        turns.add(turn("assistant", repeat("MIDDLE-", 200)));
        turns.add(turn("user", "NEWEST-tail"));
        ConversationView view = viewOf(turns);
        props.getRefine().setTokenBudget(20);

        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong())).thenReturn(VALID_JSON);

        refinery.refine(view);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(invoker)
                .invokeSync(any(), anyString(), captor.capture(), anyLong());
        String prompt = captor.getValue();
        assertTrue(prompt.contains("NEWEST-tail"), "最近消息必须保留");
        assertFalse(prompt.contains("OLDEST-OLDEST-OLDEST-"), "最早消息应被裁掉");
    }

    @Test
    public void refine_redaction_regex_replaces_sensitive_strings() throws Exception {
        ConversationView view = newView(
                "this api_key=Abcdef1234567890 in config",
                "ok");
        props.getPrivacy().setRedactPatterns(Collections.singletonList(
                "(?i)api_key=[A-Za-z0-9]+"));
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong())).thenReturn(VALID_JSON);

        refinery.refine(view);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(invoker)
                .invokeSync(any(), anyString(), captor.capture(), anyLong());
        String prompt = captor.getValue();
        assertFalse(prompt.contains("Abcdef1234567890"), "API key 应被脱敏");
        assertTrue(prompt.contains("[REDACTED]"), "应有脱敏占位符");
    }

    @Test
    public void refine_CLI_failure_throws_RefineException() throws Exception {
        ConversationView view = newView("q", "a");
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong()))
                .thenThrow(new CliInvokeException(CliInvokeException.Reason.TIMEOUT, "io"));

        assertThrows(RefineException.class, () -> refinery.refine(view));
    }

    @Test
    public void refine_JSON_parse_failure_throws_RefineException() throws Exception {
        ConversationView view = newView("q", "a");
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong()))
                .thenReturn("this is not json at all");

        RefineException ex = assertThrows(RefineException.class,
                () -> refinery.refine(view));
        assertNotNull(ex.getMessage());
    }

    @Test
    public void refine_score_out_of_range_throws_RefineException() throws Exception {
        ConversationView view = newView("q", "a");
        String invalid = VALID_JSON.replace("0.87", "1.5");
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong())).thenReturn(invalid);

        assertThrows(RefineException.class, () -> refinery.refine(view));
    }

    @Test
    public void refine_invalid_ttl_category_throws_RefineException() throws Exception {
        ConversationView view = newView("q", "a");
        String invalid = VALID_JSON.replace("\"deploy\"", "\"bogus\"");
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong())).thenReturn(invalid);

        assertThrows(RefineException.class, () -> refinery.refine(view));
    }

    @Test
    public void refine_missing_title_throws_RefineException() throws Exception {
        ConversationView view = newView("q", "a");
        String invalid = VALID_JSON.replace("\"SkipList 配置遗漏导致部署失败\"", "\"\"");
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong())).thenReturn(invalid);

        assertThrows(RefineException.class, () -> refinery.refine(view));
    }

    @Test
    public void refine_missing_triggerSignals_defaults_to_empty_array_without_throwing() throws Exception {
        ConversationView view = newView("q", "a");
        String noSignals = "{\"score\":0.7,\"ttl_category\":\"general\","
                + "\"title\":\"t\",\"context\":\"c\",\"process\":\"p\",\"conclusion\":\"c\"}";
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong())).thenReturn(noSignals);

        RefineResult res = refinery.refine(view);

        assertTrue(res.getContent().getTriggerSignals().isEmpty());
    }

    @Test
    public void refine_assistant_message_with_raw_stream_json_extracts_plain_text_strips_noise() throws Exception {
        // 复现真实污染: assistant 消息存的是原始 stream-json (init + tool_use + 终局 result)
        List<ConversationTurn> turns = new ArrayList<>();
        turns.add(turn("user", "traceId 异常排查"));
        String rawStreamJson =
                "{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[\"Bash\",\"Read\",\"Edit\"]}\n"
                + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                + "\"name\":\"Bash\",\"input\":{\"command\":\"find . -name x\"}}]}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"根因是 SkipList 配置遗漏\"}";
        turns.add(turn("assistant", rawStreamJson));
        ConversationView view = viewOf(turns);
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong())).thenReturn(VALID_JSON);

        refinery.refine(view);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(invoker)
                .invokeSync(any(), anyString(), captor.capture(), anyLong());
        String prompt = captor.getValue();
        assertTrue(prompt.contains("根因是 SkipList 配置遗漏"), "应保留终局 result 纯文本结论");
        assertFalse(prompt.contains("\"subtype\":\"init\""), "init 噪声应被剥离");
        assertFalse(prompt.contains("tool_use"), "tool_use 噪声应被剥离");
    }

    @Test
    public void refine_single_message_still_over_max_input_chars_truncates_with_marker() throws Exception {
        // user 消息不过 extract, 直接走单条截断兜底; 造一条超 maxInputChars 的纯文本
        props.getEmbedding().setMaxInputChars(100);
        List<ConversationTurn> turns = new ArrayList<>();
        turns.add(turn("user", repeat("X", 500)));
        turns.add(turn("assistant", "ok"));
        ConversationView view = viewOf(turns);
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong())).thenReturn(VALID_JSON);

        refinery.refine(view);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(invoker)
                .invokeSync(any(), anyString(), captor.capture(), anyLong());
        String prompt = captor.getValue();
        assertTrue(prompt.contains("[truncated]"), "超长单条应带截断标记");
        assertFalse(prompt.contains(repeat("X", 200)), "超出部分应被截掉");
    }

    @Test
    public void refine_transient503_should_retry_then_succeed() throws Exception {
        ConversationView view = newView("q", "a");
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        ConversationRefinery r = new ConversationRefinery(invoker, props,
                new com.example.agentweb.app.StreamOutputExtractor(), sleeps::add);
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong()))
                .thenThrow(transient503())
                .thenThrow(transient503())
                .thenReturn(VALID_JSON);

        RefineResult res = r.refine(view);

        assertEquals(0.87, res.getScore(), 1e-6);
        org.mockito.Mockito.verify(invoker, org.mockito.Mockito.times(3))
                .invokeSync(any(), anyString(), anyString(), anyLong());
        assertEquals(Arrays.asList(1000L, 2000L), sleeps, "退避序列应等比 1s→2s");
    }

    @Test
    public void refine_persistent503_should_exhaust_attempts_then_throw() throws Exception {
        ConversationView view = newView("q", "a");
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        ConversationRefinery r = new ConversationRefinery(invoker, props,
                new com.example.agentweb.app.StreamOutputExtractor(), sleeps::add);
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong()))
                .thenThrow(transient503());

        assertThrows(RefineException.class, () -> r.refine(view));

        // maxAttempts=3 → 调 3 次, sleep 2 次 (最后一次失败不再退避)
        org.mockito.Mockito.verify(invoker, org.mockito.Mockito.times(3))
                .invokeSync(any(), anyString(), anyString(), anyLong());
        assertEquals(Arrays.asList(1000L, 2000L), sleeps);
    }

    @Test
    public void refine_nonTransientError_should_not_retry() throws Exception {
        ConversationView view = newView("q", "a");
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        ConversationRefinery r = new ConversationRefinery(invoker, props,
                new com.example.agentweb.app.StreamOutputExtractor(), sleeps::add);
        // NON_ZERO_EXIT 但错误是鉴权失败 (401), 非瞬态 → 不重试
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong()))
                .thenThrow(new CliInvokeException(CliInvokeException.Reason.NON_ZERO_EXIT,
                        "CLI exited with code 1, output=401 Unauthorized: invalid api key"));

        assertThrows(RefineException.class, () -> r.refine(view));

        org.mockito.Mockito.verify(invoker, org.mockito.Mockito.times(1))
                .invokeSync(any(), anyString(), anyString(), anyLong());
        assertTrue(sleeps.isEmpty(), "非瞬态错误不应退避");
    }

    @Test
    public void refine_localTimeout_should_not_retry() throws Exception {
        ConversationView view = newView("q", "a");
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        ConversationRefinery r = new ConversationRefinery(invoker, props,
                new com.example.agentweb.app.StreamOutputExtractor(), sleeps::add);
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong()))
                .thenThrow(new CliInvokeException(CliInvokeException.Reason.TIMEOUT,
                        "CLI process timed out after 180s"));

        assertThrows(RefineException.class, () -> r.refine(view));

        org.mockito.Mockito.verify(invoker, org.mockito.Mockito.times(1))
                .invokeSync(any(), anyString(), anyString(), anyLong());
        assertTrue(sleeps.isEmpty(), "本地超时不应退避");
    }

    @Test
    public void refine_backoff_should_grow_geometrically_and_cap() throws Exception {
        ConversationView view = newView("q", "a");
        props.getRefine().getRetry().setMaxAttempts(5);
        props.getRefine().getRetry().setInitialBackoffMs(1000L);
        props.getRefine().getRetry().setMultiplier(2.0D);
        props.getRefine().getRetry().setMaxBackoffMs(3000L);
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        ConversationRefinery r = new ConversationRefinery(invoker, props,
                new com.example.agentweb.app.StreamOutputExtractor(), sleeps::add);
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong()))
                .thenThrow(transient503());

        assertThrows(RefineException.class, () -> r.refine(view));

        // 1000→2000→(4000 clamp 3000)→(8000 clamp 3000); 5 次尝试 sleep 4 次
        assertEquals(Arrays.asList(1000L, 2000L, 3000L, 3000L), sleeps);
    }

    @Test
    public void refine_maxAttempts1_should_disable_retry() throws Exception {
        ConversationView view = newView("q", "a");
        props.getRefine().getRetry().setMaxAttempts(1);
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        ConversationRefinery r = new ConversationRefinery(invoker, props,
                new com.example.agentweb.app.StreamOutputExtractor(), sleeps::add);
        when(invoker.invokeSync(any(), anyString(), anyString(), anyLong()))
                .thenThrow(transient503());

        assertThrows(RefineException.class, () -> r.refine(view));

        org.mockito.Mockito.verify(invoker, org.mockito.Mockito.times(1))
                .invokeSync(any(), anyString(), anyString(), anyLong());
        assertTrue(sleeps.isEmpty(), "maxAttempts=1 应不退避");
    }

    /** 模拟真实中转 503: NON_ZERO_EXIT, 错误文本含 "503 Service Unavailable". */
    private static CliInvokeException transient503() {
        return new CliInvokeException(CliInvokeException.Reason.NON_ZERO_EXIT,
                "CLI exited with code 1, output={\"type\":\"result\",\"subtype\":\"error\","
                        + "\"error\":{\"message\":\"unexpected status 503 Service Unavailable: "
                        + "Service temporarily unavailable, url: https://sub.mokatu.shop/v1/responses\"}}");
    }

    private ConversationView newView(String userMsg, String assistantMsg) {
        List<ConversationTurn> turns = new ArrayList<>();
        turns.add(turn("user", userMsg));
        turns.add(turn("assistant", assistantMsg));
        return viewOf(turns);
    }

    private ConversationView viewOf(List<ConversationTurn> turns) {
        return ConversationView.builder()
                .sourceId("sess-1")
                .sourceType(SourceType.CHAT)
                .agentType(AgentType.CLAUDE)
                .workingDir("/tmp")
                .turns(turns)
                .build();
    }

    private ConversationTurn turn(String role, String content) {
        return new ConversationTurn(role, content, Instant.now());
    }

    private String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
