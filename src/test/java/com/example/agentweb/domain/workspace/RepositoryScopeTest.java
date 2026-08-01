package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author alex
 * @since 2026-08-01
 */
class RepositoryScopeTest {

    @Test
    void createsImmutableScopeWithExactlyOnePrimaryAndStableOrdering() {
        RepositorySelection selection = RepositorySelection.of(
                "service-b", Arrays.asList("service-b", "service-a"));
        ResolvedRepository serviceA = repository("service-a", "/workspace/service-a");
        ResolvedRepository serviceB = repository("service-b", "/workspace/service-b");

        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection, Arrays.asList(serviceB, serviceA), 50);

        assertEquals("/workspace", scope.getWorkspaceRoot());
        assertEquals("service-b", scope.getPrimaryRepositoryKey());
        assertEquals(Arrays.asList(serviceA, serviceB), scope.getRepositories());
        assertSame(serviceB, scope.primaryRepository());
        assertSame(serviceA, scope.requireRepository("service-a"));
        assertTrue(scope.containsRepository("service-a"));
        assertFalse(scope.containsRepository("service-c"));
        assertThrows(UnsupportedOperationException.class,
                () -> scope.getRepositories().add(repository("service-c", "/workspace/service-c")));
    }

    @Test
    void rejectsEmptyResolvedRepositorySet() {
        RepositorySelection selection = RepositorySelection.of(
                "service-a", Collections.singletonList("service-a"));

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create(
                        "/workspace", selection,
                        Collections.<ResolvedRepository>emptyList(), 50));
    }

    @Test
    void enforcesConfiguredRepositoryLimit() {
        RepositorySelection selection = RepositorySelection.of(
                "service-a", Arrays.asList("service-a", "service-b"));
        List<ResolvedRepository> repositories = Arrays.asList(
                repository("service-a", "/workspace/service-a"),
                repository("service-b", "/workspace/service-b"));

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create("/workspace", selection, repositories, 1));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create("/workspace", selection, repositories, 0));
    }

    @Test
    void requiresResolvedRepositoryKeysToMatchSelectionExactly() {
        RepositorySelection selection = RepositorySelection.of(
                "service-a", Arrays.asList("service-a", "service-b"));

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create(
                        "/workspace", selection,
                        Collections.singletonList(repository(
                                "service-a", "/workspace/service-a")), 50));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create(
                        "/workspace", selection,
                        Arrays.asList(
                                repository("service-a", "/workspace/service-a"),
                                repository("service-b", "/workspace/service-b"),
                                repository("service-c", "/workspace/service-c")), 50));
    }

    @Test
    void rejectsDuplicateRepositoryKeyEvenWhenRootsDiffer() {
        RepositorySelection selection = RepositorySelection.of(
                "service-a", Collections.singletonList("service-a"));

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create(
                        "/workspace", selection,
                        Arrays.asList(
                                repository("service-a", "/workspace/service-a"),
                                repository("service-a", "/workspace/service-a-copy")), 50));
    }

    @Test
    void rejectsRepositoryOutsideWorkspaceRealRoot() {
        RepositorySelection selection = RepositorySelection.of(
                "service-a", Collections.singletonList("service-a"));

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create(
                        "/workspace", selection,
                        Collections.singletonList(repository(
                                "service-a", "/outside/service-a")), 50));
    }

    @Test
    void rejectsDuplicateRealRepositoryRoot() {
        RepositorySelection selection = RepositorySelection.of(
                "service-a", Arrays.asList("service-a", "service-b"));

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create(
                        "/workspace", selection,
                        Arrays.asList(
                                repository("service-a", "/workspace/shared"),
                                repository("service-b", "/workspace/shared")), 50));
    }

    @Test
    void rejectsRepositoriesWhoseRealRootsContainEachOther() {
        RepositorySelection selection = RepositorySelection.of(
                "service-a", Arrays.asList("service-a", "service-b"));

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create(
                        "/workspace", selection,
                        Arrays.asList(
                                repository("service-a", "/workspace/service-a"),
                                repository("service-b", "/workspace/service-a/modules/service-b")),
                        50));
    }

    @Test
    void rejectsWorkspaceRootThatIsNotAnAbsoluteNormalizedRealPath() {
        RepositorySelection selection = RepositorySelection.of(
                "service-a", Collections.singletonList("service-a"));
        ResolvedRepository repository = repository("service-a", "/workspace/service-a");

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create("workspace", selection,
                        Collections.singletonList(repository), 50));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryScope.create("/workspace/other/..", selection,
                        Collections.singletonList(repository), 50));
    }

    @Test
    void scopeHashIsStableAcrossResolvedRepositoryInputOrder() {
        RepositorySelection selection = RepositorySelection.of(
                "service-a", Arrays.asList("service-b", "service-a"));
        ResolvedRepository serviceA = repository("service-a", "/workspace/service-a");
        ResolvedRepository serviceB = repository("service-b", "/workspace/service-b");

        RepositoryScope first = RepositoryScope.create(
                "/workspace", selection, Arrays.asList(serviceA, serviceB), 50);
        RepositoryScope second = RepositoryScope.create(
                "/workspace", selection, Arrays.asList(serviceB, serviceA), 50);

        assertEquals(first.getScopeHash(), second.getScopeHash());
        assertEquals(64, first.getScopeHash().length());
    }

    @Test
    void scopeHashChangesWhenPrimaryOrRootIdentityChanges() {
        ResolvedRepository serviceA = repository("service-a", "/workspace/service-a");
        ResolvedRepository serviceB = repository("service-b", "/workspace/service-b");
        List<ResolvedRepository> repositories = Arrays.asList(serviceA, serviceB);
        RepositoryScope primaryA = RepositoryScope.create(
                "/workspace",
                RepositorySelection.of("service-a", Arrays.asList("service-a", "service-b")),
                repositories, 50);
        RepositoryScope primaryB = RepositoryScope.create(
                "/workspace",
                RepositorySelection.of("service-b", Arrays.asList("service-a", "service-b")),
                repositories, 50);
        List<ResolvedRepository> changedIdentity = new ArrayList<ResolvedRepository>(repositories);
        changedIdentity.set(1, ResolvedRepository.fromVerifiedFacts(
                "service-b", "/workspace/service-b", fingerprint("new-identity"), false));
        RepositoryScope changed = RepositoryScope.create(
                "/workspace",
                RepositorySelection.of("service-a", Arrays.asList("service-a", "service-b")),
                changedIdentity, 50);

        assertNotEquals(primaryA.getScopeHash(), primaryB.getScopeHash());
        assertNotEquals(primaryA.getScopeHash(), changed.getScopeHash());
    }

    @Test
    void matchesSnapshotTopologyWithoutExposingHashComparisonToCaller() {
        RepositorySelection selection = RepositorySelection.of(
                "service-a", Collections.singletonList("service-a"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(repository("service-a", "/workspace/service-a")), 50);
        String matchingTopologyHash = WorkspaceTopology.of(
                "/workspace", selection).getTopologyHash();
        WorkspaceSnapshotReference matching = new WorkspaceSnapshotReference(
                "snapshot-1", matchingTopologyHash, fingerprint("state-1"), 1);
        WorkspaceSnapshotReference different = new WorkspaceSnapshotReference(
                "snapshot-2", fingerprint("different-topology"), fingerprint("state-2"), 1);

        assertTrue(scope.matchesSnapshotTopology(matching));
        assertFalse(scope.matchesSnapshotTopology(different));
        assertFalse(scope.matchesSnapshotTopology(null));
    }

    @Test
    void requireRepositoryRejectsUnknownOrInvalidKeyWithoutLeakingIteration() {
        RepositoryScope scope = RepositoryScope.create(
                "/workspace",
                RepositorySelection.of("service-a", Collections.singletonList("service-a")),
                Collections.singletonList(repository("service-a", "/workspace/service-a")), 50);

        assertThrows(IllegalArgumentException.class, () -> scope.requireRepository("service-b"));
        assertThrows(IllegalArgumentException.class, () -> scope.requireRepository("../service-a"));
    }

    private static ResolvedRepository repository(String key, String root) {
        return ResolvedRepository.fromVerifiedFacts(key, root, fingerprint(key), false);
    }

    private static String fingerprint(String value) {
        return CanonicalHashing.sha256(value);
    }
}
