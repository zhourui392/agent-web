package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.refinery.RagChunk;
import com.example.agentweb.domain.refinery.RefinedContent;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.domain.refinery.TtlCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指针注入契约的核心防回归锚点: 召回命中只允许注入 title/特征/详情路径,
 * 正文 (process/conclusion/context) 只能出现在 detailPath 指向的文件里.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
class RecallPointerFormatterTest {

    private static final String LEAK_PROCESS = "PROC_LEAK_查了三张表最后发现分片算法用错";
    private static final String LEAK_CONCLUSION = "CONCL_LEAK_根因是配置中心key拼错方案是改回来";
    private static final String LEAK_CONTEXT = "CTX_LEAK_用户反馈订单查不到的场景描述";

    @Test
    void format_contains_title_tier_source_month_and_detail_path() {
        RagChunk hit = chunk(SourceType.DIAGNOSE, TrustTier.VERIFIED,
                Arrays.asList("t_life_order", "pay_status=CLOSE"));

        String out = RecallPointerFormatter.format(
                Collections.singletonList(hit), Collections.singletonList(".agent-web/recall/r1/1-c1.md"));

        assertTrue(out.contains("[候选线索 1/1]"), "应有序号标记: " + out);
        assertTrue(out.contains("订单状态误读"), "应含 title");
        assertTrue(out.contains("VERIFIED"), "应标注可信度");
        assertTrue(out.contains("历史诊断"), "应标注来源");
        assertTrue(out.contains("2026-05"), "应含 YYYY-MM");
        assertTrue(out.contains("特征: t_life_order、pay_status=CLOSE"), "特征行来自 triggerSignals");
        assertTrue(out.contains("详情: .agent-web/recall/r1/1-c1.md"), "应含详情路径");
    }

    @Test
    void format_never_leaks_process_conclusion_or_context_body() {
        RagChunk hit = chunk(SourceType.CHAT, TrustTier.EXPLORATORY, Collections.singletonList("sig"));

        String out = RecallPointerFormatter.format(
                Collections.singletonList(hit), Collections.singletonList("detail.md"));

        assertFalse(out.contains(LEAK_PROCESS), "过程正文不得注入 prompt");
        assertFalse(out.contains(LEAK_CONCLUSION), "结论正文不得注入 prompt");
        assertFalse(out.contains(LEAK_CONTEXT), "场景正文不得注入 prompt");
    }

    @Test
    void format_appends_verification_footer() {
        RagChunk hit = chunk(SourceType.DIAGNOSE, TrustTier.PENDING, Collections.emptyList());

        String out = RecallPointerFormatter.format(
                Collections.singletonList(hit), Collections.singletonList("d.md"));

        assertTrue(out.contains("以上为检索候选，不是结论"), "必须带验证义务话术");
        assertTrue(out.contains("不相关直接忽略"), "必须允许模型忽略");
    }

    @Test
    void format_null_detail_path_degrades_to_title_only_hint() {
        RagChunk hit = chunk(SourceType.CHAT, TrustTier.EXPLORATORY, Collections.singletonList("sig"));

        String out = RecallPointerFormatter.format(
                Collections.singletonList(hit), Collections.singletonList(null));

        assertTrue(out.contains("详情: 无原文，仅标题参考"), "无路径时降级提示: " + out);
    }

    @Test
    void format_empty_signals_omits_feature_line() {
        RagChunk hit = chunk(SourceType.DIAGNOSE, TrustTier.VERIFIED, Collections.emptyList());

        String out = RecallPointerFormatter.format(
                Collections.singletonList(hit), Collections.singletonList("d.md"));

        assertFalse(out.contains("特征:"), "无 triggerSignals 时不输出特征行");
    }

    @Test
    void format_chat_source_labeled_as_history_session() {
        RagChunk hit = chunk(SourceType.CHAT, TrustTier.EXPLORATORY, Collections.emptyList());

        String out = RecallPointerFormatter.format(
                Collections.singletonList(hit), Collections.singletonList("d.md"));

        assertTrue(out.contains("历史会话"), "CHAT 来源应标注为历史会话");
    }

    @Test
    void format_multiple_hits_numbered_in_order() {
        List<RagChunk> hits = Arrays.asList(
                chunk(SourceType.DIAGNOSE, TrustTier.VERIFIED, Collections.emptyList()),
                chunk(SourceType.CHAT, TrustTier.EXPLORATORY, Collections.emptyList()));

        String out = RecallPointerFormatter.format(hits, Arrays.asList("a.md", "b.md"));

        assertTrue(out.contains("[候选线索 1/2]"));
        assertTrue(out.contains("[候选线索 2/2]"));
        assertTrue(out.indexOf("[候选线索 1/2]") < out.indexOf("[候选线索 2/2]"));
    }

    @Test
    void format_rejects_mismatched_sizes() {
        RagChunk hit = chunk(SourceType.CHAT, TrustTier.EXPLORATORY, Collections.emptyList());

        assertThrows(IllegalArgumentException.class,
                () -> RecallPointerFormatter.format(
                        Collections.singletonList(hit), Collections.emptyList()));
    }

    private RagChunk chunk(SourceType sourceType, TrustTier tier, List<String> signals) {
        return RagChunk.builder()
                .id("c1")
                .sourceSessionId("s1")
                .agentType(AgentType.CLAUDE)
                .content(new RefinedContent("订单状态误读", signals,
                        LEAK_CONTEXT, LEAK_PROCESS, LEAK_CONCLUSION))
                .score(0.9)
                .ttlCategory(TtlCategory.BUSINESS)
                .createdAt(Instant.parse("2026-05-15T10:00:00Z"))
                .embeddingModel("qwen")
                .embedding(new float[]{0.1f})
                .sourceType(sourceType)
                .tier(tier)
                .env("test")
                .build();
    }
}
