package com.example.agentweb.infra.cli;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentCliProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claude CLI 方言单元测试，覆盖命令拼装、resume flag 追加、session_id 抽取与归一化直通。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
class ClaudeCliDialectTest {

    private ClaudeCliDialect dialect;
    private AgentCliProperties.Client config;

    @BeforeEach
    void setUp() {
        dialect = new ClaudeCliDialect();
        config = new AgentCliProperties.Client();
        config.setExec("claude");
        config.setArgs(Arrays.asList("--print", "--output-format", "stream-json"));
    }

    @Test
    void type_shouldReturnClaude() {
        assertEquals(AgentType.CLAUDE, dialect.type());
    }

    @Test
    void buildCommand_noResumeId_shouldUseTemplateArgs() {
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hello")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertEquals(Arrays.asList("claude", "--print", "--output-format", "stream-json"), cmd);
    }

    @Test
    void buildCommand_withMessagePlaceholder_shouldReplace() {
        config.setArgs(Arrays.asList("--prompt", "${MESSAGE}"));
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hi there")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertEquals(Arrays.asList("claude", "--prompt", "hi there"), cmd);
    }

    @Test
    void buildCommand_withResumeId_shouldAppendResumeFlag() {
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hello")
                .resumeId("abc-123")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertEquals(
                Arrays.asList("claude", "--print", "--output-format", "stream-json", "--resume", "abc-123"),
                cmd);
    }

    @Test
    void buildCommand_blankResumeId_shouldNotAppend() {
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hello")
                .resumeId("   ")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertFalse(cmd.contains("--resume"));
    }

    @Test
    void buildCommand_withModel_shouldAppendModelFlag() {
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hello")
                .model("claude-haiku-4-5-20251001")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertEquals(
                Arrays.asList("claude", "--print", "--output-format", "stream-json",
                        "--model", "claude-haiku-4-5-20251001"),
                cmd);
    }

    @Test
    void buildCommand_blankModel_shouldNotAppendModelFlag() {
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hello")
                .model("   ")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertFalse(cmd.contains("--model"));
    }

    @Test
    void buildCommand_modelAndResume_shouldAppendBoth() {
        BuildContext ctx = BuildContext.builder()
                .config(config)
                .userMessage("hello")
                .resumeId("abc-123")
                .model("haiku")
                .build();

        List<String> cmd = dialect.buildCommand(ctx);

        assertEquals(
                Arrays.asList("claude", "--print", "--output-format", "stream-json",
                        "--resume", "abc-123", "--model", "haiku"),
                cmd);
    }

    @Test
    void buildCommand_missingExec_shouldThrow() {
        config.setExec("");
        BuildContext ctx = BuildContext.builder().config(config).userMessage("x").build();

        assertThrows(IllegalStateException.class, () -> dialect.buildCommand(ctx));
    }

    @Test
    void extractResumeId_systemInitChunk_shouldReturnSessionId() {
        String chunk = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-1\"}";

        assertEquals("sess-1", dialect.extractResumeId(chunk));
    }

    @Test
    void extractResumeId_chunkWithoutField_shouldReturnNull() {
        String chunk = "{\"type\":\"message\",\"content\":\"hi\"}";

        assertNull(dialect.extractResumeId(chunk));
    }

    @Test
    void extractResumeId_invalidJson_shouldReturnNull() {
        assertNull(dialect.extractResumeId("not json"));
    }

    @Test
    void extractResumeId_emptySessionId_shouldReturnNull() {
        assertNull(dialect.extractResumeId("{\"session_id\":\"\"}"));
    }

    @Test
    void normalizeChunk_anyInput_shouldPassThroughAsSingletonList() {
        String line = "{\"type\":\"anything\"}";

        java.util.List<String> out = dialect.normalizeChunk(line);

        assertEquals(1, out.size());
        assertSame(line, out.get(0));
    }

    // ── isTurnEnd：只认 result 事件 ──

    @Test
    void isTurnEnd_resultEvent_shouldReturnTrue() {
        assertTrue(dialect.isTurnEnd("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"done\"}"));
    }

    @Test
    void isTurnEnd_nonResultEvent_shouldReturnFalse() {
        assertFalse(dialect.isTurnEnd("{\"type\":\"assistant\",\"message\":{\"content\":\"hi\"}}"));
        assertFalse(dialect.isTurnEnd("{\"type\":\"system\",\"subtype\":\"init\"}"));
    }

    @Test
    void isTurnEnd_invalidJson_shouldReturnFalse() {
        assertFalse(dialect.isTurnEnd("result but not json"));
    }

    @Test
    void isTurnEnd_null_shouldReturnFalse() {
        assertFalse(dialect.isTurnEnd(null));
    }
}
