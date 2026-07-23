package com.example.agentweb.domain.setting;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工作空间配置可用性策略测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class WorkspaceSettingsPolicyTest {

    @Test
    void requireUsable_allConfiguredDirectoriesExist_shouldPass() {
        Set<String> existing = new HashSet<String>(Arrays.asList(
                "/srv/workspace", "/srv/project", "/srv/upload"));
        WorkspaceSettingsPolicy policy = new WorkspaceSettingsPolicy(existing::contains);
        WorkspaceSettings settings = WorkspaceSettings.create(
                "/srv/project",
                Arrays.asList("/srv/workspace", "/srv/project"),
                Collections.singletonList("/srv/upload")
        );

        assertDoesNotThrow(() -> policy.requireUsable(settings));
    }

    @Test
    void requireUsable_oneConfiguredDirectoryMissing_shouldReject() {
        WorkspaceSettingsPolicy policy = new WorkspaceSettingsPolicy("/srv/workspace"::equals);
        WorkspaceSettings settings = WorkspaceSettings.create(
                "/srv/workspace",
                Arrays.asList("/srv/workspace", "/srv/missing"),
                Collections.<String>emptyList()
        );

        assertThrows(IllegalArgumentException.class, () -> policy.requireUsable(settings));
    }
}
