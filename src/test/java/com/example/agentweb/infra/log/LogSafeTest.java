package com.example.agentweb.infra.log;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogSafe} 工具方法的纯单测,无依赖。
 * 拆分覆盖 truncate / maskKey / summarizeCmd / safeLen 全部分支。
 *
 * @author zhourui(V33215020)
 * @since 2026/05/27
 */
public class LogSafeTest {

    @Test
    void truncate_nullReturnsEmpty() {
        assertEquals("", LogSafe.truncate(null));
        assertEquals("", LogSafe.truncate(null, 10));
    }

    @Test
    void truncate_underLimitReturnsAsIs() {
        assertEquals("short", LogSafe.truncate("short", 10));
        assertEquals("exact", LogSafe.truncate("exact", 5));
    }

    @Test
    void truncate_overLimitAppendsLenHint() {
        String src = "abcdefghij"; // len=10
        String out = LogSafe.truncate(src, 4);
        assertEquals("abcd...(len=10)", out);
    }

    @Test
    void truncate_defaultLimitIs200() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            sb.append('x');
        }
        String out = LogSafe.truncate(sb.toString());
        // 截到 200 + 提示
        assertTrue(out.startsWith("xxxx"));
        assertTrue(out.contains("(len=250)"));
        // 前缀长度 200
        assertEquals(200, out.indexOf("...(len="));
    }

    @Test
    void maskKey_nullReturnsEmpty() {
        assertEquals("", LogSafe.maskKey(null));
    }

    @Test
    void maskKey_shortStringFullyMasked() {
        // <= 8 字符全屏蔽
        assertEquals("***", LogSafe.maskKey("abc"));
        assertEquals("***", LogSafe.maskKey("abcdefgh"));
    }

    @Test
    void maskKey_longStringKeepsHeadAndTail() {
        // > 8 字符:首 4 + *** + 尾 4
        assertEquals("ak-o***-key", LogSafe.maskKey("ak-onnn-key"));
        assertEquals("1234***7890", LogSafe.maskKey("1234567890"));
    }

    @Test
    void maskKey_realisticApiKey() {
        String key = "sk-proj-1234567890abcdef";
        String masked = LogSafe.maskKey(key);
        assertTrue(masked.startsWith("sk-p"));
        assertTrue(masked.endsWith("cdef"));
        assertTrue(masked.contains("***"));
        // 中间细节不应泄漏
        assertTrue(!masked.contains("1234567890"));
    }

    @Test
    void summarizeCmd_nullOrEmptyReturnsBrackets() {
        assertEquals("[]", LogSafe.summarizeCmd(null));
        assertEquals("[]", LogSafe.summarizeCmd(Collections.emptyList()));
    }

    @Test
    void summarizeCmd_keepsArrayStructure() {
        String out = LogSafe.summarizeCmd(Arrays.asList("claude", "--print", "--output-format", "stream-json"));
        assertEquals("[claude, --print, --output-format, stream-json]", out);
    }

    @Test
    void summarizeCmd_nullTokenRenderedLiterally() {
        String out = LogSafe.summarizeCmd(Arrays.asList("a", null, "b"));
        assertEquals("[a, null, b]", out);
    }

    @Test
    void summarizeCmd_longTokenTruncatedToCmdLimit() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            big.append('z');
        }
        String out = LogSafe.summarizeCmd(Arrays.asList("cmd", big.toString()));
        // 内部 CMD_TOKEN_MAX_LEN = 120,所以应见 "(len=200)" 提示
        assertTrue(out.contains("(len=200)"), out);
    }

    @Test
    void safeLen_nullReturnsZero() {
        assertEquals(0, LogSafe.safeLen(null));
    }

    @Test
    void safeLen_returnsRealLength() {
        assertEquals(0, LogSafe.safeLen(""));
        assertEquals(3, LogSafe.safeLen("abc"));
        assertEquals(5, LogSafe.safeLen("中文 ok"));
    }
}
