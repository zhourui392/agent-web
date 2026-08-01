package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Workbench Domain 测试使用的已解析 Repository Scope 事实。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkbenchDomainFixtures {

    private static final String WORKSPACE_ROOT = "/workspace";
    private static final RepositorySelection SELECTION = RepositorySelection.of(
            "agent-web", Arrays.asList("agent-web", "shared-library"));

    private WorkbenchDomainFixtures() {
    }

    static RepositoryScope repositoryScope() {
        return RepositoryScope.create(
                WORKSPACE_ROOT, SELECTION,
                Arrays.asList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web", repeat('7'), false),
                        ResolvedRepository.fromVerifiedFacts(
                                "shared-library", "/workspace/shared-library",
                                repeat('8'), false)),
                50);
    }

    static WorkspaceSnapshotReference snapshotReference(
            String snapshotId, String stateHash) {
        String topologyHash = WorkspaceTopology.of(
                WORKSPACE_ROOT, SELECTION).getTopologyHash();
        return new WorkspaceSnapshotReference(
                snapshotId, topologyHash, stateHash, 2);
    }

    static List<String> repositoryKeys(RepositoryScope scope) {
        List<String> keys = new ArrayList<String>();
        scope.getRepositories().forEach(repository -> keys.add(repository.getRepositoryKey()));
        return keys;
    }

    static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
