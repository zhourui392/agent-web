package com.example.agentweb.infra.log;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志脱敏与截断工具。
 * <p>所有对外日志一律走本工具做尺寸控制与敏感信息处理。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026/05/19
 */
public final class LogSafe {

    /** 默认文本截断长度。 */
    public static final int DEFAULT_MAX_LEN = 200;

    /** 命令行参数最大字符串长度。 */
    private static final int CMD_TOKEN_MAX_LEN = 120;

    /** API Key 等敏感串保留的前后字符数。 */
    private static final int MASK_KEEP_HEAD = 4;
    private static final int MASK_KEEP_TAIL = 4;

    private LogSafe() {
        // util
    }

    /**
     * 字符串截断；超长追加 "...(len=N)" 提示原始长度。
     *
     * @param value 原始字符串，允许为 null
     * @param max   最大字符数（包含追加部分前的有效字符）
     * @return 截断后字符串，null 返回 ""
     */
    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...(len=" + value.length() + ")";
    }

    /**
     * 使用默认长度截断。
     */
    public static String truncate(String value) {
        return truncate(value, DEFAULT_MAX_LEN);
    }

    /**
     * 屏蔽敏感串（API Key / token / secret）：保留首尾若干字符，中间用 *** 替代。
     *
     * @param value 原始串，允许为 null
     * @return 形如 "ak-o***-me" 的脱敏串
     */
    public static String maskKey(String value) {
        if (value == null) {
            return "";
        }
        int len = value.length();
        if (len <= MASK_KEEP_HEAD + MASK_KEEP_TAIL) {
            return "***";
        }
        return value.substring(0, MASK_KEEP_HEAD) + "***" + value.substring(len - MASK_KEEP_TAIL);
    }

    /**
     * 命令行数组摘要：每个 token 超长截断，整体仍保留数组结构，便于排障。
     *
     * @param cmd 命令行参数
     * @return 形如 "[claude, --print, --output-format, stream-json, ...]"
     */
    public static String summarizeCmd(List<String> cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return "[]";
        }
        List<String> copy = new ArrayList<String>(cmd.size());
        for (String token : cmd) {
            copy.add(token == null ? "null" : truncate(token, CMD_TOKEN_MAX_LEN));
        }
        return copy.toString();
    }

    /**
     * 返回字符串长度；null 返回 0。便于日志统一记录 messageLen 等量化字段。
     */
    public static int safeLen(String value) {
        return value == null ? 0 : value.length();
    }
}
