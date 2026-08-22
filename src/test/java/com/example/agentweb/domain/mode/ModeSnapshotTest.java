package com.example.agentweb.domain.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ModeSnapshot JSON 冻结与 chat_session 切换语义回归。
 *
 * @author alex
 * @since 2026-08-20
 */
class ModeSnapshotTest {

    @Test
    void snapshotSurvivesJacksonRoundTrip() throws Exception {
        ModeSnapshot snapshot = new ModeSnapshot("m1", "code-review", "评审", "描述",
                "提示词", "plan", "claude-x", "high",
                List.of(new ModeCapabilities.CommandRef("cmd", "1",
                        ChatModeTest.hashOf("cmd"), 0)),
                List.of(),
                List.of(new ModeCapabilities.McpServerRef("mcp", "2",
                        ChatModeTest.hashOf("mcp"), "READ", "STDIO", 1)));
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(snapshot);
        ModeSnapshot restored = mapper.readValue(json, ModeSnapshot.class);
        assertEquals(snapshot.snapshotHash(), restored.snapshotHash());
        assertEquals("plan", restored.getPermissionMode());
        assertEquals(1, restored.getMcpServers().size());
        assertEquals("READ", restored.getMcpServers().get(0).getMaximumAccess());
    }
}
