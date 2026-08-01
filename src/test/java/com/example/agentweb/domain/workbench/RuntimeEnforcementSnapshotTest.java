package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 冻结仓库读写范围的领域投影测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeEnforcementSnapshotTest {

    @Test
    void repositoryScopeFactsShouldExposeExactFrozenReadAndWriteAccess() {
        RepositoryScope scope = scope("service-api", "agent-web", "service-api");
        RuntimeEnforcementSnapshot snapshot = RuntimeEnforcementSnapshot.modify(
                "CODEX", "0.42", scope.getScopeHash(), "service-api",
                Collections.singletonList("agent-web"), 1800L, 8_388_608L);

        List<RunRepositoryScopeFact> facts =
                snapshot.repositoryScopeFacts(scope);

        assertEquals(2, facts.size());
        assertEquals("agent-web", facts.get(0).getRepositoryKey());
        assertEquals("agent-web", facts.get(0).getRelativePath());
        assertFalse(facts.get(0).isPrimary());
        assertEquals(RunRepositoryScopeFact.Access.WRITE,
                facts.get(0).getAccess());
        assertEquals("service-api", facts.get(1).getRepositoryKey());
        assertTrue(facts.get(1).isPrimary());
        assertEquals(RunRepositoryScopeFact.Access.READ,
                facts.get(1).getAccess());
        assertThrows(UnsupportedOperationException.class,
                () -> facts.clear());
    }

    @Test
    void repositoryScopeFactsShouldRejectAnotherScope() {
        RepositoryScope frozen = scope("agent-web", "agent-web");
        RuntimeEnforcementSnapshot snapshot = RuntimeEnforcementSnapshot.readOnly(
                "CODEX", "0.42", frozen.getScopeHash(), "agent-web",
                1800L, 8_388_608L);
        RepositoryScope another = scope("service-api", "service-api");

        assertThrows(IllegalStateException.class,
                () -> snapshot.repositoryScopeFacts(another));
    }

    private static RepositoryScope scope(
            String primary, String... repositoryKeys) {
        List<String> keys = Arrays.asList(repositoryKeys);
        RepositorySelection selection = RepositorySelection.of(primary, keys);
        java.util.ArrayList<ResolvedRepository> repositories =
                new java.util.ArrayList<ResolvedRepository>();
        for (int index = 0; index < keys.size(); index++) {
            String key = keys.get(index);
            repositories.add(ResolvedRepository.fromVerifiedFacts(
                    key, "/workspace/" + key,
                    repeat((char) ('a' + index)), false));
        }
        return RepositoryScope.create(
                "/workspace", selection, repositories, 50);
    }

    private static String repeat(char value) {
        char[] characters = new char[64];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
