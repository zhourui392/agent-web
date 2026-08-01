package com.example.agentweb.domain.workspace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkspaceSnapshot 不变量与 stateHash。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkspaceSnapshotTest {

    private static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-01T10:00:05Z");
    private static final String HEAD_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String DIFF_A = repeat('a', 64);
    private static final String DIFF_B = repeat('b', 64);
    private static final String DIFF_C = repeat('c', 64);

    @Test
    void capturesMultiRepoSnapshotWithStableStateHash() {
        WorkspaceTopology topology = topology("agent-web", "service-b");
        List<RepositoryBaseline> baselines = Arrays.asList(
                cleanBaseline("service-b", "D:/ws/service-b", HEAD_B, DIFF_B),
                cleanBaseline("agent-web", "D:/ws/agent-web", HEAD_A, DIFF_A));

        WorkspaceSnapshot first = WorkspaceSnapshot.capture(
                "snap-1", purpose("WORKBENCH_CREATE"), topology, baselines,
                Collections.<WorkspaceAnomalyEvidence>emptyList(), T0, T1);
        WorkspaceSnapshot second = WorkspaceSnapshot.capture(
                "snap-2", purpose("WORKBENCH_CREATE"), topology,
                Arrays.asList(
                        cleanBaseline("agent-web", "D:/ws/agent-web", HEAD_A, DIFF_A),
                        cleanBaseline("service-b", "D:/ws/service-b", HEAD_B, DIFF_B)),
                null, T0, T1);

        assertTrue(first.isClean());
        assertEquals(purpose("WORKBENCH_CREATE"), first.getPurpose());
        assertEquals(first.getStateHash(), second.getStateHash());
        assertEquals(Arrays.asList("agent-web", "service-b"),
                Arrays.asList(first.getRepositories().get(0).getRepositoryKey(),
                        first.getRepositories().get(1).getRepositoryKey()));
        assertEquals("snap-1", first.reference().getSnapshotId());
        assertEquals(2, first.reference().getRepositoryCount());
        assertEquals(first.getTopology().getTopologyHash(), first.reference().getTopologyHash());
        assertEquals(first.getStateHash(), first.reference().getStateHash());
    }

    @Test
    void cleanFalseWhenAnyRepoDirty() {
        WorkspaceTopology topology = topology("agent-web");
        ChangedFileEvidence dirty = ChangedFileEvidence.observed(
                "src/Main.java", " M", DIFF_C);
        RepositoryBaseline baseline = RepositoryBaseline.capture(
                "agent-web", "D:/ws/agent-web", "main", HEAD_A, false, DIFF_A,
                Collections.singletonList(dirty), T1);

        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(
                "snap-dirty", purpose("WORKBENCH_CREATE"), topology,
                Collections.singletonList(baseline), null, T0, T1);

        assertFalse(snapshot.isClean());
    }

    @Test
    void cleanFalseWhenAnomalyPresentEvenIfReposClean() {
        WorkspaceTopology topology = topology("agent-web");
        WorkspaceAnomalyEvidence anomaly = WorkspaceAnomalyEvidence.of(
                WorkspaceAnomalyEvidence.Kind.SECONDARY_VERIFY_MISMATCH,
                "agent-web", "diffHash changed during capture window");

        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(
                "snap-anomaly", purpose("WORKBENCH_CREATE"), topology,
                Collections.singletonList(
                        cleanBaseline("agent-web", "D:/ws/agent-web", HEAD_A, DIFF_A)),
                Collections.singletonList(anomaly), T0, T1);

        assertFalse(snapshot.isClean());
        assertEquals(1, snapshot.getAnomalies().size());
    }

    @Test
    void rejectsBaselinesNotMatchingTopology() {
        WorkspaceTopology topology = topology("agent-web", "service-b");
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceSnapshot.capture(
                        "snap-x", purpose("WORKBENCH_CREATE"), topology,
                        Collections.singletonList(
                                cleanBaseline("agent-web", "D:/ws/agent-web", HEAD_A, DIFF_A)),
                        null, T0, T1));
    }

    @Test
    void rejectsExtraBaseline() {
        WorkspaceTopology topology = topology("agent-web");
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceSnapshot.capture(
                        "snap-x", purpose("WORKBENCH_CREATE"), topology,
                        Arrays.asList(
                                cleanBaseline("agent-web", "D:/ws/agent-web", HEAD_A, DIFF_A),
                                cleanBaseline("service-b", "D:/ws/service-b", HEAD_B, DIFF_B)),
                        null, T0, T1));
    }

    @Test
    void rejectsCapturedBeforeStarted() {
        WorkspaceTopology topology = topology("agent-web");
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceSnapshot.capture(
                        "snap-x", purpose("WORKBENCH_CREATE"), topology,
                        Collections.singletonList(
                                cleanBaseline("agent-web", "D:/ws/agent-web", HEAD_A, DIFF_A)),
                        null, T1, T0));
    }

    @Test
    void stateHashChangesWhenRepoHeadChanges() {
        WorkspaceTopology topology = topology("agent-web");
        WorkspaceSnapshot before = WorkspaceSnapshot.capture(
                "snap-a", purpose("WORKBENCH_CREATE"), topology,
                Collections.singletonList(
                        cleanBaseline("agent-web", "D:/ws/agent-web", HEAD_A, DIFF_A)),
                null, T0, T1);
        WorkspaceSnapshot after = WorkspaceSnapshot.capture(
                "snap-b", purpose("WORKBENCH_RUN_END"), topology,
                Collections.singletonList(
                        cleanBaseline("agent-web", "D:/ws/agent-web", HEAD_B, DIFF_A)),
                null, T0, T1);

        assertNotEquals(before.getStateHash(), after.getStateHash());
        assertTrue(before.sameTopology(after));
        assertFalse(before.sameState(after));
    }

    @Test
    void requireRepositoryReturnsBaseline() {
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(
                "snap-1", purpose("WORKBENCH_CREATE"), topology("agent-web"),
                Collections.singletonList(
                        cleanBaseline("agent-web", "D:/ws/agent-web", HEAD_A, DIFF_A)),
                null, T0, T1);

        assertEquals(HEAD_A, snapshot.requireRepository("agent-web").getHead());
        assertThrows(IllegalArgumentException.class,
                () -> snapshot.requireRepository("missing"));
    }

    @Test
    void singleRepoN1Compatible() {
        WorkspaceTopology topology = topology("agent-web");
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(
                "snap-n1", purpose("WORKBENCH_CREATE"), topology,
                Collections.singletonList(
                        cleanBaseline("agent-web", "D:/ws/agent-web", HEAD_A, DIFF_A)),
                null, T0, T1);

        assertEquals(1, snapshot.getRepositories().size());
        assertTrue(snapshot.isClean());
        assertEquals(topology.getTopologyHash(), snapshot.reference().getTopologyHash());
    }

    private static WorkspaceTopology topology(String primary, String... others) {
        List<String> keys = new java.util.ArrayList<String>();
        keys.add(primary);
        Collections.addAll(keys, others);
        return WorkspaceTopology.of("D:/ws", RepositorySelection.of(primary, keys));
    }

    private static RepositoryBaseline cleanBaseline(String key, String root, String head,
                                                    String diffHash) {
        return RepositoryBaseline.capture(key, root, "main", head, true, diffHash, T1);
    }

    private static SnapshotPurpose purpose(String value) {
        return SnapshotPurpose.of(value);
    }

    private static String repeat(char ch, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, ch);
        return new String(chars);
    }
}
