package com.example.agentweb.infra.cli;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentCliProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codex CLI 方言单元测试。
 * <p>双路径覆盖：
 * <ul>
 *   <li>真实 codex exec 拼装：{@code cfg.args} 为空时按 {@code codex exec [resume <id>] --json ... -} 拼装</li>
 *   <li>Legacy 模板路径：{@code cfg.args} 非空时仍走模板渲染，为 stub 测试与历史兼容保留</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
class CodexCliDialectTest {

    private CodexCliDialect dialect;
    private AgentCliProperties.Client config;

    @BeforeEach
    void setUp() {
        dialect = new CodexCliDialect();
        config = new AgentCliProperties.Client();
        config.setExec("codex");
    }

    @Test
    void type_shouldReturnCodex() {
        assertEquals(AgentType.CODEX, dialect.type());
    }

    // ── 真实 codex exec 拼装路径 ──

    @Test
    void buildCommand_noArgs_shouldBuildCodexExecWithAllFlags() {
        config.setArgs(Collections.<String>emptyList());
        config.setModel("gpt-5.1-codex");
        config.setSandboxBypass(true);
        config.setSkipGitCheck(true);
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hello")
                .workingDir("/work")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertEquals(Arrays.asList(
                "codex", "exec",
                "--json",
                "--skip-git-repo-check",
                "--dangerously-bypass-approvals-and-sandbox",
                "--cd", "/work",
                "--model", "gpt-5.1-codex",
                "-"
        ), cmd);
    }

    @Test
    void buildCommand_ctxModel_shouldOverrideCfgModel() {
        config.setArgs(Collections.<String>emptyList());
        config.setModel("gpt-5.1-codex");
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hello")
                .workingDir("/work")
                .model("o4-mini")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        int idx = cmd.indexOf("--model");
        assertTrue(idx >= 0, "应拼 --model");
        assertEquals("o4-mini", cmd.get(idx + 1), "ctx.model 应覆盖 cfg.model");
        assertFalse(cmd.contains("gpt-5.1-codex"), "cfg.model 应被覆盖");
    }

    @Test
    void buildCommand_blankCtxModel_shouldFallBackToCfgModel() {
        config.setArgs(Collections.<String>emptyList());
        config.setModel("gpt-5.1-codex");
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hello")
                .workingDir("/work")
                .model("  ")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        int idx = cmd.indexOf("--model");
        assertEquals("gpt-5.1-codex", cmd.get(idx + 1), "ctx.model 空应回退 cfg.model");
    }

    @Test
    void buildCommand_noArgs_withResumeId_shouldUseExecResumeSubcommand() {
        config.setArgs(Collections.<String>emptyList());
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi")
                .resumeId("th-abc")
                .workingDir("/work")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertEquals("codex", cmd.get(0));
        assertEquals("exec", cmd.get(1));
        assertEquals("resume", cmd.get(2));
        assertEquals("th-abc", cmd.get(3));
        assertEquals("-", cmd.get(cmd.size() - 1));
    }

    @Test
    void buildCommand_noArgs_withResumeId_shouldOmitCdFlag() {
        // codex exec resume 不接受 --cd (强制继承原会话 cwd), 拼上会导致进程 exit 2
        config.setArgs(Collections.<String>emptyList());
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi")
                .resumeId("th-abc")
                .workingDir("/work")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertFalse(cmd.contains("--cd"), "exec resume 不应拼 --cd");
    }

    @Test
    void buildCommand_noArgs_blankResumeId_shouldUseNewSession() {
        config.setArgs(Collections.<String>emptyList());
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi")
                .resumeId("   ")
                .workingDir("/work")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertFalse(cmd.contains("resume"), "blank resumeId 不应触发 resume 子命令");
    }

    @Test
    void buildCommand_noArgs_modelOmitted_shouldSkipModelFlag() {
        config.setArgs(Collections.<String>emptyList());
        config.setModel(null);
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi")
                .workingDir("/work")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertFalse(cmd.contains("--model"));
    }

    @Test
    void buildCommand_noArgs_sandboxBypassFalse_shouldOmitFlag() {
        config.setArgs(Collections.<String>emptyList());
        config.setSandboxBypass(false);
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi")
                .workingDir("/work")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertFalse(cmd.contains("--dangerously-bypass-approvals-and-sandbox"));
    }

    @Test
    void buildCommand_noArgs_skipGitCheckFalse_shouldOmitFlag() {
        config.setArgs(Collections.<String>emptyList());
        config.setSkipGitCheck(false);
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi")
                .workingDir("/work")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertFalse(cmd.contains("--skip-git-repo-check"));
    }

    @Test
    void buildCommand_noArgs_extraArgs_shouldAppendBeforeStdinSentinel() {
        config.setArgs(Collections.<String>emptyList());
        config.setExtraArgs(Arrays.asList("--profile", "ci"));
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi")
                .workingDir("/work")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        int profileIdx = cmd.indexOf("--profile");
        int stdinIdx = cmd.lastIndexOf("-");
        assertTrue(profileIdx > 0 && profileIdx < stdinIdx, "extra args 必须出现在 stdin sentinel '-' 之前");
        assertEquals("ci", cmd.get(profileIdx + 1));
    }

    @Test
    void buildCommand_noArgs_missingWorkingDir_shouldSkipCdFlag() {
        config.setArgs(Collections.<String>emptyList());
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertFalse(cmd.contains("--cd"));
    }

    // ── Legacy 模板路径（兼容 stub 测试与历史配置） ──

    @Test
    void buildCommand_legacyTemplateArgs_shouldRenderTemplate() {
        config.setArgs(Arrays.asList("--print", "${MESSAGE}"));
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertEquals(Arrays.asList("codex", "--print", "hi"), cmd);
    }

    @Test
    void buildCommand_legacyTemplateArgs_shouldNotAppendResumeFlag() {
        config.setArgs(Arrays.asList("--print", "${MESSAGE}"));
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi")
                .resumeId("legacy-id")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertFalse(cmd.contains("--resume"));
        assertFalse(cmd.contains("resume"), "模板模式下不应注入 resume 子命令");
    }

    // ── extractResumeId：只认 thread.started.thread_id ──

    @Test
    void extractResumeId_threadStarted_shouldReturnThreadId() {
        String chunk = "{\"type\":\"thread.started\",\"thread_id\":\"th-xyz\"}";

        assertEquals("th-xyz", dialect.extractResumeId(chunk));
    }

    @Test
    void extractResumeId_otherEvent_shouldReturnNull() {
        assertNull(dialect.extractResumeId("{\"type\":\"turn.started\"}"));
    }

    @Test
    void extractResumeId_oldSessionIdField_shouldReturnNull() {
        // Codex 不使用 session_id 字段，必须只认 thread_id 避免误抓 MCP / 工具事件中的同名字段
        assertNull(dialect.extractResumeId("{\"session_id\":\"old-claude-id\"}"));
    }

    @Test
    void extractResumeId_threadStartedWithEmptyId_shouldReturnNull() {
        assertNull(dialect.extractResumeId("{\"type\":\"thread.started\",\"thread_id\":\"\"}"));
    }

    @Test
    void extractResumeId_invalidJson_shouldReturnNull() {
        assertNull(dialect.extractResumeId("not json"));
    }

    @Test
    void normalizeChunk_threadStarted_shouldDelegateToNormalizerAndEmitInit() {
        // 验证 dialect 真的把 normalizeChunk 委托给了 CodexEventNormalizer (而非保持直通)
        // 字段映射的细节覆盖在 CodexEventNormalizerTest, 这里只验证委托接通
        String line = "{\"type\":\"thread.started\",\"thread_id\":\"th-abc\"}";

        List<String> out = dialect.normalizeChunk(line);

        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("\"type\":\"system\""));
        assertTrue(out.get(0).contains("\"session_id\":\"th-abc\""));
    }

    @Test
    void normalizeChunk_unknownLine_shouldReturnEmptyList() {
        // 旧的直通行为已废弃: 未识别事件返回空 list (而非原样回传)
        List<String> out = dialect.normalizeChunk("{\"type\":\"unknown.future.event\"}");

        assertTrue(out.isEmpty());
    }

    // ── isTurnEnd：只认 turn.completed / turn.failed ──

    @Test
    void isTurnEnd_turnCompleted_shouldReturnTrue() {
        assertTrue(dialect.isTurnEnd("{\"type\":\"turn.completed\",\"usage\":{\"output_tokens\":5}}"));
    }

    @Test
    void isTurnEnd_turnFailed_shouldReturnTrue() {
        assertTrue(dialect.isTurnEnd("{\"type\":\"turn.failed\",\"error\":{\"message\":\"boom\"}}"));
    }

    @Test
    void isTurnEnd_nonTerminalEvent_shouldReturnFalse() {
        assertFalse(dialect.isTurnEnd("{\"type\":\"turn.started\"}"));
        assertFalse(dialect.isTurnEnd("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\"}}"));
    }

    @Test
    void isTurnEnd_invalidJson_shouldReturnFalse() {
        assertFalse(dialect.isTurnEnd("turn.completed but not json"));
    }

    @Test
    void isTurnEnd_null_shouldReturnFalse() {
        assertFalse(dialect.isTurnEnd(null));
    }
}
