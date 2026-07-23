package com.example.agentweb.app;

import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Worktree 工作空间路径授权编排测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(MockitoExtension.class)
class WorktreeServicePathPolicyTest {

    @Mock
    private WorkspacePathPolicy workspacePathPolicy;

    @TempDir
    private Path authorizedWorkspace;

    @Test
    void listWorktrees_shouldUseSharedWorkspacePathPolicy() throws Exception {
        // Given
        String requestedWorkspace = "/requested/workspace";
        when(workspacePathPolicy.prepareWorkspaceDirectory(requestedWorkspace))
                .thenReturn(authorizedWorkspace.toString());
        WorktreeService service = new WorktreeService(workspacePathPolicy);

        // When
        List<Map<String, Object>> result = service.listWorktrees(null, requestedWorkspace);

        // Then
        assertTrue(result.isEmpty());
        verify(workspacePathPolicy).prepareWorkspaceDirectory(requestedWorkspace);
    }

    @Test
    void listWorktrees_shouldRejectWorkspaceDeniedBySharedPolicy() {
        // Given
        String requestedWorkspace = "/denied/workspace";
        when(workspacePathPolicy.prepareWorkspaceDirectory(requestedWorkspace))
                .thenThrow(new IllegalArgumentException("Path out of allowed roots"));
        WorktreeService service = new WorktreeService(workspacePathPolicy);

        // When / Then
        assertThrows(IllegalArgumentException.class,
                () -> service.listWorktrees(null, requestedWorkspace));
    }

    @Test
    void switchBranch_shouldUseSharedWorkspacePathPolicy() throws Exception {
        // Given
        String requestedWorkspace = "/requested/workspace";
        when(workspacePathPolicy.requireExistingDirectory(requestedWorkspace))
                .thenReturn(authorizedWorkspace.toString());
        WorktreeService service = new WorktreeService(workspacePathPolicy);

        // When
        Map<String, Object> result = service.switchBranch(null, requestedWorkspace, "feature/path-policy");

        // Then
        assertTrue(String.valueOf(result.get("worktreePath")).startsWith(authorizedWorkspace.toString()));
        verify(workspacePathPolicy).requireExistingDirectory(requestedWorkspace);
    }
}
