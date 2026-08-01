package com.example.agentweb.domain.workspace;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkspaceTopology：成员身份 + topologyHash（含绝对 workspaceRoot）。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkspaceTopologyTest {

    @Test
    void buildsStableTopologyHashFromAbsoluteRootAndSortedKeys() {
        RepositorySelection selection = RepositorySelection.of(
                "service-b", Arrays.asList("service-b", "agent-web"));
        WorkspaceTopology first = WorkspaceTopology.of(
                "D:/delivery", selection);
        WorkspaceTopology second = WorkspaceTopology.of(
                "D:/delivery",
                RepositorySelection.of("service-b", Arrays.asList("agent-web", "service-b")));

        assertEquals(first.getTopologyHash(), second.getTopologyHash());
        assertEquals("service-b", first.getPrimaryRepositoryKey());
        assertEquals(Arrays.asList("agent-web", "service-b"), first.getRepositoryKeys());
        assertEquals(
                "7416cbd545db0c88d305f13b4fb053535afbb49b5866a469845f344125a0cf27",
                first.getTopologyHash());
        assertTrue(first.getTopologyHash().matches("[a-f0-9]{64}"));
    }

    @Test
    void differentWorkspaceRootProducesDifferentHash() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        WorkspaceTopology left = WorkspaceTopology.of("D:/ws-a", selection);
        WorkspaceTopology right = WorkspaceTopology.of("D:/ws-b", selection);

        assertNotEquals(left.getTopologyHash(), right.getTopologyHash());
    }

    @Test
    void differentPrimaryProducesDifferentHash() {
        WorkspaceTopology left = WorkspaceTopology.of(
                "D:/delivery",
                RepositorySelection.of("agent-web", Arrays.asList("agent-web", "service-b")));
        WorkspaceTopology right = WorkspaceTopology.of(
                "D:/delivery",
                RepositorySelection.of("service-b", Arrays.asList("agent-web", "service-b")));

        assertNotEquals(left.getTopologyHash(), right.getTopologyHash());
    }

    @Test
    void sameTopologyRequiresSameRootPrimaryAndKeys() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        WorkspaceTopology base = WorkspaceTopology.of("D:/delivery", selection);

        assertTrue(base.sameTopology(WorkspaceTopology.of("D:/delivery", selection)));
        assertTrue(!base.sameTopology(WorkspaceTopology.of("D:/other", selection)));
    }

    @Test
    void rejectsBlankWorkspaceRoot() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceTopology.of("  ",
                        RepositorySelection.of("a", Collections.singletonList("a"))));
    }

    @Test
    void normalizesWindowsWorkspaceRootSeparators() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        WorkspaceTopology mixed = WorkspaceTopology.of("D:\\delivery\\ws", selection);
        WorkspaceTopology posix = WorkspaceTopology.of("D:/delivery/ws", selection);

        assertEquals(mixed.getTopologyHash(), posix.getTopologyHash());
        assertEquals("D:/delivery/ws", mixed.getWorkspaceRoot());
    }
}
