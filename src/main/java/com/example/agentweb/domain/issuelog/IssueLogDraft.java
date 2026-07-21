package com.example.agentweb.domain.issuelog;

import lombok.Getter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * issue-log 草稿值对象。承载用户/LLM 草稿编辑期的全部字段,保存时被 {@link IssueLogEntry} 包装为聚合根。
 *
 * <p>不变量:</p>
 * <ul>
 *   <li>{@code title} 必填,自动 trim 前后空白</li>
 *   <li>{@code categories} / {@code services} 至少 1 个非空 token,空 token 自动过滤</li>
 *   <li>{@code triggerSignals} 允许 0 个 token(编辑期可空,归档时 {@link #requireArchivable()} 校验);
 *       空 token 自动过滤</li>
 *   <li>{@code slug} 可选,归一化为小写 kebab-case(仅 [a-z0-9-]);无 ASCII 内容时归一化为 {@code null},
 *       文件名回落到标题 slug 化(可能保留 CJK)</li>
 *   <li>{@code phenomenon} / {@code rootCause} / {@code solution} / {@code notes} 允许空字符串,
 *       {@code null} 归一化为 {@code ""}</li>
 *   <li>所有列表对外暴露为不可变视图</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public final class IssueLogDraft {

    @Getter
    private final String title;
    @Getter
    private final List<String> categories;
    @Getter
    private final List<String> services;
    @Getter
    private final List<String> triggerSignals;
    @Getter
    private final String phenomenon;
    @Getter
    private final String rootCause;
    @Getter
    private final String solution;
    @Getter
    private final String notes;
    /** 文件名用英文短标识(小写 kebab),可空;空时文件名回落到标题 slug 化。 */
    @Getter
    private final String slug;

    public IssueLogDraft(String title,
                         List<String> categories,
                         List<String> services,
                         List<String> triggerSignals,
                         String phenomenon,
                         String rootCause,
                         String solution,
                         String notes,
                         String slug) {
        this.title = requireNonBlankTitle(title);
        this.categories = sanitizeTokenList(categories, "categories");
        this.services = sanitizeTokenList(services, "services");
        this.triggerSignals = sanitizeOptionalTokenList(triggerSignals);
        this.phenomenon = nullToEmpty(phenomenon);
        this.rootCause = nullToEmpty(rootCause);
        this.solution = nullToEmpty(solution);
        this.notes = nullToEmpty(notes);
        this.slug = sanitizeSlug(slug);
    }

    /** 无 slug 的 8 参构造,slug 默认 {@code null}(文件名回落到标题)。 */
    public IssueLogDraft(String title,
                         List<String> categories,
                         List<String> services,
                         List<String> triggerSignals,
                         String phenomenon,
                         String rootCause,
                         String solution,
                         String notes) {
        this(title, categories, services, triggerSignals,
                phenomenon, rootCause, solution, notes, null);
    }

    /** 兼容老 7 参构造(triggerSignals 默认空),仅用于历史代码迁移过渡;新代码请走 8 参。 */
    public IssueLogDraft(String title,
                         List<String> categories,
                         List<String> services,
                         String phenomenon,
                         String rootCause,
                         String solution,
                         String notes) {
        this(title, categories, services, Collections.emptyList(),
                phenomenon, rootCause, solution, notes);
    }

    /**
     * 触发词 — 用户/客服可能搜索的症状关键词列表(3~5 个,编辑期允许空)。
     * 真正消费者是 INDEX.md 的"触发词"列,供 agent 在该列上 grep 命中相关 issue。
     */

    /**
     * 归档前置校验:触发词非空才允许落盘。
     * 编辑期(草稿弹窗 / 候选审核)允许触发词为空,但落成正式 issue-log 时必须补齐——
     * 触发词列是关键词召回命中本条记录的入口,空触发词的记录约等于白写。
     *
     * @throws IllegalArgumentException 触发词为空
     */
    public void requireArchivable() {
        if (triggerSignals.isEmpty()) {
            throw new IllegalArgumentException(
                    "触发词不能为空:请补充错误码、报错原文、表字段或用户症状短语后再保存,否则该记录无法被召回");
        }
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

    private static List<String> sanitizeTokenList(List<String> source, String fieldName) {
        if (source == null) {
            throw new IllegalArgumentException(fieldName + " required");
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
        if (sink.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must contain at least one non-blank value");
        }
        return Collections.unmodifiableList(sink);
    }

    /**
     * 类同 {@link #sanitizeTokenList} 但允许结果为空——空 token 过滤、{@code null} 视为空列表,
     * 不强制至少 1 个值。triggerSignals 是辅助字段,LLM 漏抽 / 旧数据缺失时不该让整条草稿判废。
     */
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

    /** 归一化为小写 kebab-case:非 [a-z0-9] 连续段折叠为单个 {@code -},去首尾 {@code -};无内容归 {@code null}。 */
    private static String sanitizeSlug(String raw) {
        if (raw == null) {
            return null;
        }
        String kebab = raw.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return kebab.isEmpty() ? null : kebab;
    }
}
