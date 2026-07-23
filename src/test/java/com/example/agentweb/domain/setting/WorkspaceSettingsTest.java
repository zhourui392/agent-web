package com.example.agentweb.domain.setting;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工作空间运行配置领域测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class WorkspaceSettingsTest {

    @Test
    void create_validSettings_shouldNormalizePathsAndKeepDefaultFirstInEffectiveRoots() {
        WorkspaceSettings settings = WorkspaceSettings.create(
                "/srv/workspace/../workspace/project",
                Arrays.asList("/srv/workspace", "/srv/workspace/project"),
                Collections.singletonList("/srv/upload/../upload")
        );

        assertEquals("/srv/workspace/project", settings.getDefaultWorkspace());
        assertEquals(Arrays.asList("/srv/workspace", "/srv/workspace/project"),
                settings.getWorkspaceRoots());
        assertEquals(Arrays.asList("/srv/workspace/project", "/srv/workspace"),
                settings.effectiveWorkspaceRoots());
        assertEquals(Collections.singletonList("/srv/upload"), settings.getUploadRoots());
    }

    @Test
    void create_defaultOutsideConfiguredRoots_shouldReject() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceSettings.create(
                "/srv/default",
                Collections.singletonList("/srv/workspace"),
                Collections.<String>emptyList()
        ));
    }

    @Test
    void create_emptyWorkspaceRoots_shouldReject() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceSettings.create(
                "/srv/workspace",
                Collections.<String>emptyList(),
                Collections.<String>emptyList()
        ));
    }

    @Test
    void create_relativePath_shouldReject() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceSettings.create(
                "workspace",
                Collections.singletonList("workspace"),
                Collections.<String>emptyList()
        ));
    }

    @Test
    void create_duplicateRoots_shouldReject() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceSettings.create(
                "/srv/workspace",
                Arrays.asList("/srv/workspace", "/srv/./workspace"),
                Collections.<String>emptyList()
        ));
    }
}
