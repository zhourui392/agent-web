package com.example.agentweb.infra.cli;

import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentCliProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentCliInvokerImpl} 集成测,通过平台 shell(sh -c / cmd /c)模拟 agent CLI 子进程,
 * 覆盖成功/超时/非零退码/未注册 dialect 四类路径。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
@Tag("process-integration")
public class AgentCliInvokerImplTest {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    private AgentCliProperties props;

    @BeforeEach
    public void setUp() {
        props = new AgentCliProperties();
        // Claude 客户端不写 stdin 以便 echo 类命令立即结束
        props.getClaude().setStdin(false);
        props.getClaude().setExec("dummy");
    }

    @Test
    public void normal_exit_should_return_extracted_plain_text(@TempDir Path workingDir) {
        StubCliDialect dialect = new StubCliDialect(AgentType.CLAUDE,
                echoCmd("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"hello-from-stub\"}"));
        AgentCliInvokerImpl invoker = newInvoker(dialect);

        String text = invoker.invokeSync(AgentType.CLAUDE, workingDir.toString(),
                "ignored prompt", 10L);

        assertEquals("hello-from-stub", text.trim());
    }

    @Test
    public void timeout_should_throw_TIMEOUT() throws IOException {
        // 不用 @TempDir: 超时会强杀仍以该目录为 CWD 的子进程, Windows 上句柄释放有滞后,
        // @TempDir 的严格删除会偶发失败 (与 JDK 版本无关)。自管目录 + 容忍式清理, 与超时断言解耦。
        Path workingDir = Files.createTempDirectory("agent-cli-invoker-timeout");
        try {
            StubCliDialect dialect = new StubCliDialect(AgentType.CLAUDE, sleepCmd(5));
            AgentCliInvokerImpl invoker = newInvoker(dialect);

            CliInvokeException ex = assertThrows(CliInvokeException.class,
                    () -> invoker.invokeSync(AgentType.CLAUDE, workingDir.toString(),
                            "p", 1L));
            assertEquals(CliInvokeException.Reason.TIMEOUT, ex.getReason());
        } finally {
            deleteBestEffort(workingDir);
        }
    }

    /** Windows 强杀进程后目录句柄释放有滞后, 重试删除; 清理失败仅忽略, 不影响测试断言。 */
    private static void deleteBestEffort(Path dir) {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.deleteIfExists(dir);
                return;
            } catch (IOException retryable) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Test
    public void non_zero_exit_code_should_throw_NON_ZERO_EXIT(@TempDir Path workingDir) {
        StubCliDialect dialect = new StubCliDialect(AgentType.CLAUDE, exitCmd(7));
        AgentCliInvokerImpl invoker = newInvoker(dialect);

        CliInvokeException ex = assertThrows(CliInvokeException.class,
                () -> invoker.invokeSync(AgentType.CLAUDE, workingDir.toString(),
                        "p", 5L));
        assertEquals(CliInvokeException.Reason.NON_ZERO_EXIT, ex.getReason());
        assertNotNull(ex.getMessage());
    }

    @Test
    public void unregistered_dialect_should_throw_IO_FAILURE(@TempDir Path workingDir) {
        AgentCliInvokerImpl invoker = new AgentCliInvokerImpl(
                Collections.<CliDialect>emptyList(), props, new StreamOutputExtractor());

        CliInvokeException ex = assertThrows(CliInvokeException.class,
                () -> invoker.invokeSync(AgentType.CLAUDE, workingDir.toString(),
                        "p", 5L));
        assertEquals(CliInvokeException.Reason.IO_FAILURE, ex.getReason());
    }

    @Test
    public void stdout_with_stream_json_assistant_text_should_be_extracted(@TempDir Path workingDir) {
        // 用 ASCII 避免 Windows cmd GBK 与 Java UTF-8 解码冲突
        String streamJson = "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"final-conclusion\"}";
        StubCliDialect dialect = new StubCliDialect(AgentType.CLAUDE, echoCmd(streamJson));
        AgentCliInvokerImpl invoker = newInvoker(dialect);

        String text = invoker.invokeSync(AgentType.CLAUDE, workingDir.toString(), "p", 10L);

        assertTrue(text.contains("final-conclusion"), "should extract final result: " + text);
    }

    /**
     * 回归:codex dialect 的原始 NDJSON 必须经 normalizeChunk 翻译再喂给抽取器。
     * 这里 stub 模拟 codex 风格——原始 echo 出去的 raw 行不含 StreamOutputExtractor 认识的 type,
     * 必须靠 normalizeChunk 包装成 {@code type=result} 后才能被抽出文本。
     * 改回归前 invoker 直接落 raw buffer,返回空串,触发本 case 失败。
     */
    @Test
    public void raw_NDJSON_should_pass_through_normalize_chunk_before_extraction(@TempDir Path workingDir) {
        String rawCodexLine = "{\"type\":\"turn.completed\",\"payload\":\"opaque\"}";
        StubCliDialect dialect = new StubCliDialect(AgentType.CLAUDE, echoCmd(rawCodexLine)) {
            @Override
            public List<String> normalizeChunk(String stdoutLine) {
                return Collections.singletonList(
                        "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"normalized-payload\"}");
            }
        };
        AgentCliInvokerImpl invoker = newInvoker(dialect);

        String text = invoker.invokeSync(AgentType.CLAUDE, workingDir.toString(), "p", 10L);

        assertEquals("normalized-payload", text.trim(),
                "invoker should pipe stdout through dialect.normalizeChunk before extraction");
    }

    @Test
    public void five_arg_version_should_propagate_model_into_build_context(@TempDir Path workingDir) {
        CapturingDialect dialect = new CapturingDialect(AgentType.CLAUDE,
                echoCmd("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\"}"));
        AgentCliInvokerImpl invoker = newInvoker(dialect);

        invoker.invokeSync(AgentType.CLAUDE, workingDir.toString(), "p", 10L,
                "claude-haiku-4-5-20251001");

        assertEquals("claude-haiku-4-5-20251001", dialect.lastModel);
    }

    @Test
    public void four_arg_version_should_delegate_to_five_arg_with_null_model(@TempDir Path workingDir) {
        CapturingDialect dialect = new CapturingDialect(AgentType.CLAUDE,
                echoCmd("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"ok\"}"));
        AgentCliInvokerImpl invoker = newInvoker(dialect);

        invoker.invokeSync(AgentType.CLAUDE, workingDir.toString(), "p", 10L);

        assertNull(dialect.lastModel, "四参版不应下发 model");
    }

    @Test
    public void non_zero_exit_overlong_output_exception_message_should_be_truncated_keeping_error_line(@TempDir Path workingDir) {
        // 模拟真实场景: 子进程先吐一大段 init JSON (上千字符), 末尾才是真正的错误行, 然后非零退出。
        // 异常消息应截尾 (含 "chars omitted" 标记 + 末尾错误行), 不把整段刷进日志。
        StubCliDialect dialect = new StubCliDialect(AgentType.CLAUDE,
                longThenFailCmd(2000, "AUTH_FAILED_real_error_line"));
        AgentCliInvokerImpl invoker = newInvoker(dialect);

        CliInvokeException ex = assertThrows(CliInvokeException.class,
                () -> invoker.invokeSync(AgentType.CLAUDE, workingDir.toString(), "p", 10L));

        assertEquals(CliInvokeException.Reason.NON_ZERO_EXIT, ex.getReason());
        String msg = ex.getMessage();
        assertTrue(msg.contains("AUTH_FAILED_real_error_line"),
                "应保留末尾真正的错误行: " + msg);
        assertTrue(msg.contains("chars omitted"),
                "超长输出应被截尾标记: " + msg);
        // 截尾后异常消息远小于原始 2000+ 字符输出
        assertTrue(msg.length() < 1200, "截尾后消息长度应受控: len=" + msg.length());
    }

    private AgentCliInvokerImpl newInvoker(CliDialect dialect) {
        return new AgentCliInvokerImpl(Arrays.asList(dialect), props, new StreamOutputExtractor());
    }

    private static List<String> echoCmd(String text) {
        if (IS_WINDOWS) {
            return Arrays.asList("cmd", "/c", "echo " + text);
        }
        return Arrays.asList("sh", "-c", "printf '%s\\n' " + shellQuote(text));
    }

    private static List<String> sleepCmd(int seconds) {
        if (IS_WINDOWS) {
            // 用 powershell 内置 Start-Sleep,不产生孤儿子进程
            return Arrays.asList("powershell", "-NoProfile", "-Command",
                    "Start-Sleep -Seconds " + seconds);
        }
        return Arrays.asList("sh", "-c", "sleep " + seconds);
    }

    private static List<String> exitCmd(int code) {
        if (IS_WINDOWS) {
            return Arrays.asList("cmd", "/c", "exit /b " + code);
        }
        return Arrays.asList("sh", "-c", "exit " + code);
    }

    /** 先打印 padChars 个填充字符 + 一行真正的错误标记, 再非零退出。用于验证异常消息截尾。 */
    private static List<String> longThenFailCmd(int padChars, String errorMarker) {
        if (IS_WINDOWS) {
            // PowerShell: 输出一长串 'x' + 错误行, 再 exit 1
            return Arrays.asList("powershell", "-NoProfile", "-Command",
                    "Write-Output ('x' * " + padChars + "); Write-Output '" + errorMarker + "'; exit 1");
        }
        return Arrays.asList("sh", "-c",
                "head -c " + padChars + " /dev/zero | tr '\\0' x; echo; echo " + errorMarker + "; exit 1");
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 仅供测试使用的 CliDialect stub,直接返回预设命令。 */
    private static class StubCliDialect implements CliDialect {
        private final AgentType type;
        private final List<String> command;

        StubCliDialect(AgentType type, List<String> command) {
            this.type = type;
            this.command = command;
        }

        @Override
        public AgentType type() {
            return type;
        }

        @Override
        public List<String> buildCommand(BuildContext ctx) {
            return command;
        }

        @Override
        public String extractResumeId(String stdoutLine) {
            return null;
        }

        @Override
        public List<String> normalizeChunk(String stdoutLine) {
            return Collections.singletonList(stdoutLine);
        }

        @Override
        public boolean isTurnEnd(String stdoutLine) {
            return true;
        }
    }

    /** 记录最近一次 buildCommand 收到的 BuildContext.model, 用于验证 invoker 的 model 透传。 */
    private static class CapturingDialect extends StubCliDialect {
        private String lastModel;

        CapturingDialect(AgentType type, List<String> command) {
            super(type, command);
        }

        @Override
        public List<String> buildCommand(BuildContext ctx) {
            this.lastModel = ctx.getModel();
            return super.buildCommand(ctx);
        }
    }
}
