package com.example.agentweb.domain.mode;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatMode 聚合不变量回归。
 *
 * @author alex
 * @since 2026-08-20
 */
class ChatModeTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void createValidatesIdentifierPattern() {
        assertThrows(IllegalArgumentException.class, () -> ChatMode.create(
                "m1", "u1", "Illegal Id", "名称", null, null, null, null, null, null,
                ModeCapabilities.empty(), NOW));
        assertThrows(IllegalArgumentException.class, () -> ChatMode.create(
                "m1", "u1", "-bad", "名称", null, null, null, null, null, null,
                ModeCapabilities.empty(), NOW));
    }

    @Test
    void createRejectsBlankDisplayNameAndIllegalEffort() {
        assertThrows(IllegalArgumentException.class, () -> ChatMode.create(
                "m1", "u1", "code-review", " ", null, null, null, null, null, null,
                ModeCapabilities.empty(), NOW));
        assertThrows(IllegalArgumentException.class, () -> ChatMode.create(
                "m1", "u1", "code-review", "评审", null, null, null, null, "ultra", null,
                ModeCapabilities.empty(), NOW));
    }

    @Test
    void editBumpsVersionAndAppliesValidatedChanges() {
        ChatMode mode = ChatMode.create("m1", "u1", "code-review", "评审",
                null, null, ModePermissionMode.PLAN, null, "high", null,
                ModeCapabilities.empty(), NOW);
        assertEquals(0, mode.getVersion());
        mode.edit("新名称", null, "角色提示词", ModePermissionMode.AUTO, "claude-x", null,
                null, NOW.plusSeconds(60));
        assertEquals(1, mode.getVersion());
        assertEquals("新名称", mode.getDisplayName());
        assertEquals("角色提示词", mode.getDefaultPrompt());
        assertEquals(ModePermissionMode.AUTO, mode.getPermissionMode());
        assertEquals("claude-x", mode.getModel());
    }

    @Test
    void requireOwnedByHidesForeignModesAsNotFound() {
        ChatMode mode = ChatMode.create("m1", "u1", "code-review", "评审",
                null, null, null, null, null, null, ModeCapabilities.empty(), NOW);
        mode.requireOwnedBy("u1");
        assertThrows(ModeNotFoundException.class, () -> mode.requireOwnedBy("u2"));
    }

    @Test
    void snapshotFreezesCapabilityReferences() {
        ModeCapabilities capabilities = new ModeCapabilities(
                List.of(new ModeCapabilities.CommandRef(
                        "cmd", "1", hashOf("cmd"), 0)),
                List.of(new ModeCapabilities.SkillRef(
                        "skill", "2", hashOf("skill"), 1)),
                List.of());
        ChatMode mode = ChatMode.create("m1", "u1", "code-review", "评审",
                "描述", "提示词", ModePermissionMode.ACCEPT_EDITS, null, null, null,
                capabilities, NOW);
        ModeSnapshot snapshot = mode.snapshot();
        assertEquals("m1", snapshot.getSourceModeId());
        assertEquals("acceptEdits", snapshot.getPermissionMode());
        assertEquals(1, snapshot.getCommands().size());
        assertEquals(1, snapshot.getSkills().size());
        assertNotNull(snapshot.snapshotHash());
        // 快照之后编辑模式不影响已冻结快照
        mode.edit("改名", null, null, null, null, null, null, NOW.plusSeconds(1));
        assertEquals("评审", snapshot.getDisplayName());
        // 快照内容变化会改变 hash
        ModeSnapshot other = ChatMode.create("m2", "u1", "other", "评审",
                "描述", "提示词", ModePermissionMode.ACCEPT_EDITS, null, null, null,
                capabilities, NOW).snapshot();
        assertTrue(!snapshot.snapshotHash().equals(other.snapshotHash()));
    }

    @Test
    void nullPermissionModeMeansFlagAbsent() {
        ChatMode mode = ChatMode.create("m1", "u1", "plain", "无权限模式",
                null, null, null, null, null, null, ModeCapabilities.empty(), NOW);
        assertNull(mode.snapshot().getPermissionMode());
        assertThrows(IllegalArgumentException.class,
                () -> ModePermissionMode.fromCliValue("default"));
        assertEquals(ModePermissionMode.PLAN, ModePermissionMode.fromCliValue("plan"));
        assertNull(ModePermissionMode.fromCliValue(null));
    }

    static String hashOf(String content) {
        return com.example.agentweb.domain.shared.CanonicalHashing.sha256(content);
    }
}
