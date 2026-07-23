package com.example.agentweb.infra;

import com.example.agentweb.app.setting.WorkspaceSettingsQueryService;
import com.example.agentweb.domain.setting.WorkspaceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 真实路径策略应在每次校验时读取最新运行配置。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class RealPathWorkspacePolicyRuntimeSettingsTest {

    @TempDir
    Path firstRoot;

    @TempDir
    Path secondRoot;

    @Test
    void requireExistingDirectory_runtimeSettingsChanged_shouldApplyImmediately() {
        AtomicReference<WorkspaceSettings> current = new AtomicReference<WorkspaceSettings>(settings(firstRoot));
        WorkspaceSettingsQueryService queryService = current::get;
        RealPathWorkspacePolicy policy = new RealPathWorkspacePolicy(queryService);

        assertEquals(firstRoot.toString(), policy.requireExistingDirectory(firstRoot.toString()));

        current.set(settings(secondRoot));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireExistingDirectory(firstRoot.toString()));
        assertEquals(secondRoot.toString(), policy.requireExistingDirectory(secondRoot.toString()));
    }

    private WorkspaceSettings settings(Path root) {
        return WorkspaceSettings.create(root.toString(), Collections.singletonList(root.toString()),
                Collections.<String>emptyList());
    }
}
