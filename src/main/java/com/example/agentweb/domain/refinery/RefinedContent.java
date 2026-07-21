package com.example.agentweb.domain.refinery;

import lombok.Getter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LLM 精炼输出的内容五字段, 与 refinery-refine-prompt.md 的 JSON 字段一一对应.
 *
 * <p>不变量:</p>
 * <ul>
 *   <li>{@code title} 必填, 自动 trim, 不能空白</li>
 *   <li>{@code triggerSignals} 允许空, null 归一化为空列表; 空 token 自动过滤</li>
 *   <li>{@code context} / {@code process} / {@code conclusion} 允许空字符串, null 归一化为 ""</li>
 *   <li>列表对外不可变视图</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public final class RefinedContent {

    @Getter
    private final String title;
    @Getter
    private final List<String> triggerSignals;
    /** 触发场景一句话描述 (M4): 比关键词更完整的"什么时候该想起这条知识", 参与 embed 文本. */
    @Getter
    private final String triggerDescription;
    @Getter
    private final String context;
    @Getter
    private final String process;
    @Getter
    private final String conclusion;

    /** 5 参构造 (M4 前存量调用方与历史行), triggerDescription 归一化为空串. */
    public RefinedContent(String title,
                          List<String> triggerSignals,
                          String context,
                          String process,
                          String conclusion) {
        this(title, triggerSignals, null, context, process, conclusion);
    }

    public RefinedContent(String title,
                          List<String> triggerSignals,
                          String triggerDescription,
                          String context,
                          String process,
                          String conclusion) {
        this.title = requireNonBlankTitle(title);
        this.triggerSignals = sanitizeOptionalTokenList(triggerSignals);
        this.triggerDescription = nullToEmpty(triggerDescription).trim();
        this.context = nullToEmpty(context);
        this.process = nullToEmpty(process);
        this.conclusion = nullToEmpty(conclusion);
    }

    private static String requireNonBlankTitle(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("title required");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("title cannot be blank");
        }
        return trimmed;
    }

    private static List<String> sanitizeOptionalTokenList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sink = new ArrayList<>(source.size());
        for (String token : source) {
            if (token == null) {
                continue;
            }
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                sink.add(trimmed);
            }
        }
        return sink.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(sink);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
