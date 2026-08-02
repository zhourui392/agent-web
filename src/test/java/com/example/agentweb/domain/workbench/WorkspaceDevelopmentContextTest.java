package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository Scope 级安全开发上下文值对象测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkspaceDevelopmentContextTest {

    private final RepositoryDevelopmentContextClassifier classifier =
            new RepositoryDevelopmentContextClassifier();

    @Test
    void shouldFreezeRepositoriesInStableOrderAndExposeThePrimaryRepositorySemantically() {
        RepositoryDevelopmentContext frontend = classifier.classify("frontend",
                EnumSet.of(RepositoryDevelopmentMarker.PACKAGE_JSON));
        RepositoryDevelopmentContext backend = classifier.classify("backend",
                EnumSet.of(
                        RepositoryDevelopmentMarker.POM_XML,
                        RepositoryDevelopmentMarker.CONTRIBUTING_MARKDOWN));

        WorkspaceDevelopmentContext context = WorkspaceDevelopmentContext.create(
                repeat('a'), "backend", Arrays.asList(frontend, backend));
        WorkspaceDevelopmentContext reordered = WorkspaceDevelopmentContext.create(
                repeat('a'), "backend", Arrays.asList(backend, frontend));

        assertEquals(Arrays.asList("backend", "frontend"), context.repositoryKeys());
        assertEquals("backend", context.primaryRepository().getRepositoryKey());
        assertEquals(reordered, context);
        assertEquals(reordered.getContextHash(), context.getContextHash());
        assertTrue(context.hasDetectedDevelopmentMetadata());
        assertFalse(context.getContextHash().contains("/"));
    }

    @Test
    void contextHashShouldChangeWhenDetectedRepositoryFactsChange() {
        RepositoryDevelopmentContext maven = classifier.classify("agent-web",
                EnumSet.of(RepositoryDevelopmentMarker.POM_XML));
        RepositoryDevelopmentContext gradle = classifier.classify("agent-web",
                EnumSet.of(RepositoryDevelopmentMarker.BUILD_GRADLE));

        WorkspaceDevelopmentContext first = WorkspaceDevelopmentContext.create(
                repeat('b'), "agent-web", Collections.singletonList(maven));
        WorkspaceDevelopmentContext second = WorkspaceDevelopmentContext.create(
                repeat('b'), "agent-web", Collections.singletonList(gradle));

        assertNotEquals(first.getContextHash(), second.getContextHash());
    }

    @Test
    void shouldRejectMissingPrimaryDuplicateRepositoryAndInvalidScopeHash() {
        RepositoryDevelopmentContext repository = classifier.classify("agent-web",
                Collections.<RepositoryDevelopmentMarker>emptySet());

        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceDevelopmentContext.create(
                        repeat('c'), "missing", Collections.singletonList(repository)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceDevelopmentContext.create(
                        repeat('c'), "agent-web", Arrays.asList(repository, repository)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceDevelopmentContext.create(
                        "not-a-hash", "agent-web", Collections.singletonList(repository)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceDevelopmentContext.create(
                        repeat('c'), "agent-web",
                        Collections.<RepositoryDevelopmentContext>emptyList()));
    }

    private String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
