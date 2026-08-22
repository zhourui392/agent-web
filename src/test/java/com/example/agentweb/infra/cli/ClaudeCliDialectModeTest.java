package com.example.agentweb.infra.cli;

import com.example.agentweb.config.cli.AgentCliProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claude 方言模式能力下发 flag 回归。
 *
 * @author alex
 * @since 2026-08-20
 */
class ClaudeCliDialectModeTest {

    private final ClaudeCliDialect dialect = new ClaudeCliDialect();

    private AgentCliProperties.Client client(String... args) {
        AgentCliProperties.Client client = new AgentCliProperties.Client();
        client.setExec("claude");
        client.setArgs(new ArrayList<>(Arrays.asList(args)));
        return client;
    }

    @Test
    void defaultPermissionModeIsAcceptEdits() {
        List<String> cmd = dialect.buildCommand(BuildContext.builder()
                .config(client("--print", "--output-format", "stream-json"))
                .userMessage("hi").build());
        assertEquals(List.of("claude", "--print", "--output-format", "stream-json",
                "--permission-mode", "acceptEdits"), cmd);
    }

    @Test
    void modeOverridesAreAppended() {
        List<String> cmd = dialect.buildCommand(BuildContext.builder()
                .config(client("--print"))
                .userMessage("hi")
                .appendSystemPrompt("你是评审员")
                .permissionMode("plan")
                .mcpConfigPath("/tmp/mcp.json")
                .capabilityDir("/tmp/plugin/agent-mode")
                .build());
        assertTrue(cmd.contains("--append-system-prompt"));
        assertEquals("你是评审员", cmd.get(cmd.indexOf("--append-system-prompt") + 1));
        assertTrue(cmd.contains("--strict-mcp-config"));
        assertTrue(cmd.contains("--mcp-config"));
        assertEquals("/tmp/mcp.json", cmd.get(cmd.indexOf("--mcp-config") + 1));
        assertTrue(cmd.contains("--plugin-dir"));
        assertEquals("/tmp/plugin/agent-mode", cmd.get(cmd.indexOf("--plugin-dir") + 1));
        assertEquals("plan", cmd.get(cmd.indexOf("--permission-mode") + 1));
        // permission-mode 只出现一次
        assertEquals(1, countFlag(cmd, "--permission-mode"));
    }

    @Test
    void templatePermissionModePairIsStrippedOnOverride() {
        List<String> cmd = dialect.buildCommand(BuildContext.builder()
                .config(client("--print", "--permission-mode", "acceptEdits"))
                .userMessage("hi")
                .permissionMode("bypassPermissions")
                .build());
        assertEquals(1, countFlag(cmd, "--permission-mode"));
        assertEquals("bypassPermissions",
                cmd.get(cmd.indexOf("--permission-mode") + 1));
        assertFalse(cmd.contains("acceptEdits"));
    }

    @Test
    void absentOptionalFlagsAddNothing() {
        List<String> cmd = dialect.buildCommand(BuildContext.builder()
                .config(client("--print"))
                .userMessage("hi").build());
        assertFalse(cmd.contains("--append-system-prompt"));
        assertFalse(cmd.contains("--mcp-config"));
        assertFalse(cmd.contains("--strict-mcp-config"));
        assertFalse(cmd.contains("--plugin-dir"));
    }

    private static int countFlag(List<String> cmd, String flag) {
        int count = 0;
        for (String token : cmd) {
            if (flag.equals(token)) {
                count++;
            }
        }
        return count;
    }
}
