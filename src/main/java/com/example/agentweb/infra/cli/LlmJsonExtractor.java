package com.example.agentweb.infra.cli;

/**
 * 从 LLM 原始输出中裁出第一个完整 JSON 对象的字符串切片。统一 issue-log 子域内
 * {@code IssueLogRefinery}、{@code IssueLogMerger}、{@code IssueLogDedupMatcher}
 * 三处各自实现的"找 JSON"逻辑。
 *
 * <p>识别下列三种形态:</p>
 * <ul>
 *   <li>纯 JSON 对象,直接返回</li>
 *   <li>被 <code>```json ... ```</code> 代码块包裹,先剥 fence</li>
 *   <li>前后含解释文字,用字符串感知的大括号匹配截取首个 JSON 对象</li>
 * </ul>
 *
 * <p>调用方按自身错误语义包装 {@link IllegalArgumentException}:Refinery 包装为
 * {@code RefineException}、Merger 包装为 {@code MergeException}、DedupMatcher
 * 在外层 try-catch 兜底为 {@code MatchResult.failOpen()}。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-24
 */
public final class LlmJsonExtractor {

    private static final int ABBREVIATE_LIMIT = 200;

    private LlmJsonExtractor() {
    }

    /**
     * 抽取首个完整 JSON 对象。
     *
     * @param raw LLM 原始输出
     * @return 首个 JSON 对象的字符串切片
     * @throws IllegalArgumentException 入参为 {@code null} / 找不到 JSON / 大括号不闭合
     */
    public static String extractFirstObject(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("LLM output is null");
        }
        String text = stripCodeFence(raw.trim());
        int start = text.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("no JSON object found in LLM output: " + abbreviate(text));
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("unbalanced JSON braces: " + abbreviate(text));
    }

    private static String stripCodeFence(String text) {
        String t = text.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstLineEnd = t.indexOf('\n');
        if (firstLineEnd > 0) {
            t = t.substring(firstLineEnd + 1);
        }
        int lastFence = t.lastIndexOf("```");
        if (lastFence > 0) {
            t = t.substring(0, lastFence);
        }
        return t.trim();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= ABBREVIATE_LIMIT
                ? text
                : text.substring(0, ABBREVIATE_LIMIT) + "...";
    }
}
