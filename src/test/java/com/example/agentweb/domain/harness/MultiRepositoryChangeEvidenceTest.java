package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MultiRepositoryChangeEvidence：同拓扑两次快照的多仓库 diff。
 *
 * @author zhourui(V33215020)
 * @since 2026-08-01
 */
class MultiRepositoryChangeEvidenceTest {

    private static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-01T10:00:05Z");
    private static final Instant T2 = Instant.parse("2026-08-01T10:01:00Z");
    private static final String HEAD_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String DIFF_A = repeat('1', 64);
    private static final String DIFF_B = repeat('2', 64);
    private static final String DIFF_C = repeat('3', 64);

    @Test
    void detectsFileChangeInOneRepoOnly() {
        WorkspaceTopology topology = topology("agent-web", "service-b");
        WorkspaceSnapshot baseline = snapshot("snap-1", topology,
                clean("agent-web", HEAD_A, DIFF_A),
                clean("service-b", HEAD_B, DIFF_B));

        ChangedFileEvidence changed = ChangedFileEvidence.observed(
                "src/App.java", " M", DIFF_C);
        RepositoryBaseline dirtyAgent = RepositoryBaseline.capture(
                "agent-web", "D:/ws/agent-web", "main", HEAD_A, false, DIFF_C,
                Collections.singletonList(changed), T2);
        WorkspaceSnapshot current = WorkspaceSnapshot.capture(
                "snap-2", WorkspaceSnapshot.PURPOSE_IMPLEMENTATION, topology,
                Arrays.asList(dirtyAgent, clean("service-b", HEAD_B, DIFF_B)),
                null, T1, T2);

        MultiRepositoryChangeEvidence evidence =
                MultiRepositoryChangeEvidence.between(baseline, current);

        assertTrue(evidence.hasChanges());
        assertEquals(1, evidence.changedRepositories().size());
        assertEquals("agent-web", evidence.changedRepositories().get(0).getRepositoryKey());
        assertEquals(1, evidence.changedRepositories().get(0).getFiles().size());
    }

    @Test
    void noChangeWhenIdentical() {
        WorkspaceTopology topology = topology("agent-web");
        WorkspaceSnapshot baseline = snapshot("snap-1", topology,
                clean("agent-web", HEAD_A, DIFF_A));
        WorkspaceSnapshot current = snapshot("snap-2", topology,
                clean("agent-web", HEAD_A, DIFF_A));

        MultiRepositoryChangeEvidence evidence =
                MultiRepositoryChangeEvidence.between(baseline, current);

        assertFalse(evidence.hasChanges());
        assertTrue(evidence.changedRepositories().isEmpty());
    }

    @Test
    void rejectsDifferentTopology() {
        WorkspaceSnapshot left = snapshot("snap-1", topology("agent-web"),
                clean("agent-web", HEAD_A, DIFF_A));
        WorkspaceSnapshot right = snapshot("snap-2", topology("service-b"),
                clean("service-b", HEAD_B, DIFF_B));

        assertThrows(IllegalArgumentException.class,
                () -> MultiRepositoryChangeEvidence.between(left, right));
    }

    private static WorkspaceTopology topology(String primary, String... others) {
        java.util.List<String> keys = new java.util.ArrayList<String>();
        keys.add(primary);
        Collections.addAll(keys, others);
        return WorkspaceTopology.of("D:/ws", RepositorySelection.of(primary, keys));
    }

    private static WorkspaceSnapshot snapshot(String id, WorkspaceTopology topology,
                                              RepositoryBaseline... baselines) {
        return WorkspaceSnapshot.capture(id, WorkspaceSnapshot.PURPOSE_CREATE, topology,
                Arrays.asList(baselines), null, T0, T1);
    }

    private static RepositoryBaseline clean(String key, String head, String diffHash) {
        return RepositoryBaseline.capture(key, "D:/ws/" + key, "main", head, true, diffHash, T1);
    }

    private static String repeat(char ch, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, ch);
        return new String(chars);
    }
}
