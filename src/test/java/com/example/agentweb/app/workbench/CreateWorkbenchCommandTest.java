package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 创建 Workbench 命令的规范输入与幂等 Hash 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class CreateWorkbenchCommandTest {

    @Test
    void requestHashShouldIgnoreRepositoryInputOrderAndNormalizeWorkspacePath() {
        CreateWorkbenchCommand first = command(
                "/workspace/./", Arrays.asList("shared-library", "agent-web"),
                "实现工作台");
        CreateWorkbenchCommand second = command(
                "/workspace", Arrays.asList("agent-web", "shared-library"),
                "实现工作台");

        assertEquals(first.getRequestHash(), second.getRequestHash());
        assertEquals("/workspace", first.getWorkspaceRoot());
        assertEquals(Arrays.asList("agent-web", "shared-library"),
                first.getRepositorySelection().getRepositoryKeys());
    }

    @Test
    void requestHashShouldChangeWhenCanonicalBusinessInputChanges() {
        CreateWorkbenchCommand first = command(
                "/workspace", Arrays.asList("agent-web", "shared-library"),
                "实现工作台");
        CreateWorkbenchCommand changedGoal = command(
                "/workspace", Arrays.asList("agent-web", "shared-library"),
                "实现另一个目标");

        assertNotEquals(first.getRequestHash(), changedGoal.getRequestHash());
    }

    @Test
    void requestHashShouldIgnoreStageClickOrderButIncludeCatalogVersion() {
        CreateWorkbenchCommand first = command(
                "/workspace", Arrays.asList("agent-web", "shared-library"),
                "实现工作台", Arrays.asList("implementation", "requirement-analysis"),
                3L);
        CreateWorkbenchCommand reordered = command(
                "/workspace", Arrays.asList("agent-web", "shared-library"),
                "实现工作台", Arrays.asList("requirement-analysis", "implementation"),
                3L);
        CreateWorkbenchCommand changedCatalog = command(
                "/workspace", Arrays.asList("agent-web", "shared-library"),
                "实现工作台", Arrays.asList("requirement-analysis", "implementation"),
                4L);

        assertEquals(first.getRequestHash(), reordered.getRequestHash());
        assertNotEquals(first.getRequestHash(), changedCatalog.getRequestHash());
        assertEquals(Arrays.asList("implementation", "requirement-analysis"),
                first.getStageDefinitionIdentifiers());
    }

    @Test
    void commandShouldRejectRelativeWorkspaceAndInvalidSelection() {
        assertThrows(IllegalArgumentException.class,
                () -> command("workspace", Arrays.asList("agent-web"), "目标"));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateWorkbenchCommand(
                        "create-key", "Workbench", "目标", AgentType.CODEX, "local",
                        "/workspace", "missing", Arrays.asList("agent-web"),
                        Arrays.asList("requirement-analysis"), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateWorkbenchCommand(
                        "create-key", "Workbench", "目标", AgentType.CODEX, "local",
                        "/workspace", "agent-web", Arrays.asList("agent-web"),
                        java.util.Collections.emptyList(), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateWorkbenchCommand(
                        "create-key", "Workbench", "目标", AgentType.CODEX, "local",
                        "/workspace", "agent-web", Arrays.asList("agent-web"),
                        Arrays.asList("requirement-analysis"), 0L));
    }

    private static CreateWorkbenchCommand command(
            String workspaceRoot, java.util.List<String> repositories, String goal) {
        return new CreateWorkbenchCommand(
                "create-key", "Workbench MVP", goal, AgentType.CODEX, "local",
                workspaceRoot, "agent-web", repositories,
                Arrays.asList("requirement-analysis", "implementation"), 3L);
    }

    private static CreateWorkbenchCommand command(
            String workspaceRoot, java.util.List<String> repositories, String goal,
            java.util.List<String> stageDefinitions, long catalogVersion) {
        return new CreateWorkbenchCommand(
                "create-key", "Workbench MVP", goal, AgentType.CODEX, "local",
                workspaceRoot, "agent-web", repositories,
                stageDefinitions, catalogVersion);
    }
}
