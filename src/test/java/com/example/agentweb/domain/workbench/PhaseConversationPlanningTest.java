package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase Conversation 创建与重启准备语义的聚合单测。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseConversationPlanningTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T08:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");
    private static final WorkbenchPhase PHASE = WorkbenchPhase.IMPLEMENT_TEST;

    @Test
    void ensurePlanShouldExposeTrustedProvisioningFactsWithoutMutatingWorkbench() {
        Workbench workbench = newWorkbench();

        PhaseConversationProvisioning plan = workbench.planConversationEnsure(
                PHASE, OWNER, 0L);

        assertFalse(plan.hasCurrentConversation());
        assertNull(plan.getCurrentConversationId());
        assertEquals(WORKBENCH_ID, plan.getWorkbenchId());
        assertEquals(PHASE, plan.getPhase());
        assertEquals(OWNER, plan.getOwner());
        assertEquals(AgentType.CODEX, plan.getAgentType());
        assertEquals("local", plan.getEnvironment());
        assertEquals("/workspace/agent-web", plan.getPrimaryRepositoryRoot());
        assertEquals("workbench-1:IMPLEMENT_TEST", plan.getContextId());
        assertEquals(0L, workbench.getVersion());
    }

    @Test
    void ensurePlanShouldReturnExistingConversationAndStillRequireActiveOwnerVersion() {
        Workbench workbench = newWorkbench();
        workbench.bindConversation(PHASE, "session-0", OWNER, CREATED_AT.plusSeconds(1));

        PhaseConversationProvisioning plan = workbench.planConversationEnsure(
                PHASE, OWNER, 1L);

        assertTrue(plan.hasCurrentConversation());
        assertEquals("session-0", plan.getCurrentConversationId());
        assertVersionConflict(() -> workbench.planConversationEnsure(PHASE, OWNER, 0L));
        assertOwnerRequired(() -> workbench.planConversationEnsure(
                PHASE, OwnerReference.of("foreign", "Other"), 1L));

        workbench.archive(OWNER, CREATED_AT.plusSeconds(2));
        WorkbenchDomainException archived = assertThrows(WorkbenchDomainException.class,
                () -> workbench.planConversationEnsure(PHASE, OWNER, 2L));
        assertEquals(WorkbenchErrorCode.ARCHIVED, archived.getCode());
    }

    @Test
    void restartPlanShouldRequireCurrentIdleInProgressPhaseBeforeProvisioning() {
        Workbench workbench = newWorkbench();
        WorkbenchDomainException notStarted = assertThrows(WorkbenchDomainException.class,
                () -> workbench.planConversationRestart(PHASE, OWNER, 0L));
        assertEquals(WorkbenchErrorCode.PHASE_RESTART_INVALID, notStarted.getCode());

        workbench.bindConversation(PHASE, "session-0", OWNER, CREATED_AT.plusSeconds(1));
        workbench.prepareRun(PHASE, "run-1", RunMode.MODIFY_WORKSPACE,
                OWNER, CREATED_AT.plusSeconds(2));
        WorkbenchDomainException active = assertThrows(WorkbenchDomainException.class,
                () -> workbench.planConversationRestart(PHASE, OWNER, 2L));
        assertEquals(WorkbenchErrorCode.PHASE_RESTART_INVALID, active.getCode());

        workbench.finishRun(PHASE, "run-1", CREATED_AT.plusSeconds(3));
        PhaseConversationProvisioning plan = workbench.planConversationRestart(
                PHASE, OWNER, 3L);
        assertEquals("session-0", plan.getCurrentConversationId());
        assertTrue(plan.hasCurrentConversation());
    }

    private void assertVersionConflict(Runnable action) {
        WorkbenchDomainException error = assertThrows(WorkbenchDomainException.class, action::run);
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, error.getCode());
    }

    private void assertOwnerRequired(Runnable action) {
        WorkbenchDomainException error = assertThrows(WorkbenchDomainException.class, action::run);
        assertEquals(WorkbenchErrorCode.OWNER_REQUIRED, error.getCode());
    }

    private Workbench newWorkbench() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(ResolvedRepository.fromVerifiedFacts(
                        "agent-web", "/workspace/agent-web", repeat('1'), false)), 10);
        WorkspaceTopology topology = WorkspaceTopology.of("/workspace", selection);
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Phase Conversation", "独立阶段会话",
                AgentType.CODEX, "local", scope,
                new WorkspaceSnapshotReference(
                        "snapshot-1", topology.getTopologyHash(), repeat('2'), 1),
                CREATED_AT);
    }

    private String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
