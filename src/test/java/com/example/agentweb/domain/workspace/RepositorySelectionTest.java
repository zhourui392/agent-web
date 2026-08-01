package com.example.agentweb.domain.workspace;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RepositorySelection 不变量：非空、唯一主仓库、相对路径规范化、禁止嵌套。
 *
 * @author alex
 * @since 2026-08-01
 */
class RepositorySelectionTest {

    @Test
    void createsSortedSelectionWithPrimary() {
        RepositorySelection selection = RepositorySelection.of(
                "service-b",
                Arrays.asList("service-b", "platform/order-service", "agent-web"));

        assertEquals("service-b", selection.getPrimaryRepositoryKey());
        assertEquals(Arrays.asList("agent-web", "platform/order-service", "service-b"),
                selection.getRepositoryKeys());
    }

    @Test
    void normalizesBackslashAndTrimsSlash() {
        RepositorySelection selection = RepositorySelection.of(
                "platform/order-service",
                Collections.singletonList("platform\\order-service/"));

        assertEquals(Collections.singletonList("platform/order-service"),
                selection.getRepositoryKeys());
        assertEquals("platform/order-service", selection.getPrimaryRepositoryKey());
    }

    @Test
    void rejectsEmptySelection() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RepositorySelection.of("a", Collections.<String>emptyList()));
        assertTrue(ex.getMessage().contains("at least one"));
    }

    @Test
    void rejectsPrimaryOutsideSelection() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RepositorySelection.of("missing", Collections.singletonList("agent-web")));
        assertTrue(ex.getMessage().contains("primary"));
    }

    @Test
    void rejectsAbsolutePath() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositorySelection.of("/tmp/repo", Collections.singletonList("/tmp/repo")));
        assertThrows(IllegalArgumentException.class,
                () -> RepositorySelection.of("C:/repo", Collections.singletonList("C:/repo")));
    }

    @Test
    void rejectsDotAndParentSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositorySelection.of(".", Collections.singletonList(".")));
        assertThrows(IllegalArgumentException.class,
                () -> RepositorySelection.of("a/../b", Collections.singletonList("a/../b")));
        assertThrows(IllegalArgumentException.class,
                () -> RepositorySelection.of("..", Collections.singletonList("..")));
    }

    @Test
    void rejectsDuplicatesAfterNormalization() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RepositorySelection.of("agent-web",
                        Arrays.asList("agent-web", "agent-web/", "agent-web\\")));
        assertTrue(ex.getMessage().toLowerCase().contains("duplicate"));
    }

    @Test
    void rejectsNestedRepositories() {
        IllegalArgumentException parentChild = assertThrows(IllegalArgumentException.class,
                () -> RepositorySelection.of("platform",
                        Arrays.asList("platform", "platform/order-service")));
        assertTrue(parentChild.getMessage().toLowerCase().contains("nested")
                || parentChild.getMessage().toLowerCase().contains("contain"));

        IllegalArgumentException childParent = assertThrows(IllegalArgumentException.class,
                () -> RepositorySelection.of("platform/order-service",
                        Arrays.asList("platform/order-service", "platform")));
        assertTrue(childParent.getMessage().toLowerCase().contains("nested")
                || childParent.getMessage().toLowerCase().contains("contain"));
    }

    @Test
    void singleRepositoryIsValidN1() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        assertEquals(1, selection.getRepositoryKeys().size());
        assertTrue(selection.contains("agent-web"));
    }

    @Test
    void repositoryKeysAreUnmodifiable() {
        RepositorySelection selection = RepositorySelection.of(
                "a", Arrays.asList("a", "b"));
        List<String> keys = selection.getRepositoryKeys();
        assertThrows(UnsupportedOperationException.class, () -> keys.add("c"));
    }
}
