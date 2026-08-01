package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPhaseStatus;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workbench 人工生命周期与阶段会话的 Owner、乐观版本和持久化编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchLifecycleAppServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference FOREIGN = OwnerReference.of("owner-2", "Other");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-lifecycle-1");
    private static final WorkbenchPhase PHASE = WorkbenchPhase.REQUIREMENT_ANALYSIS;

    private WorkbenchRepository repository;
    private WorkbenchLifecycleAppService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkbenchRepository.class);
        service = new WorkbenchLifecycleAppService(
                repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void loadShouldReturnOwnerScopedLifecycleViewAndObscureMissingOrForeignWorkbench() {
        // Given
        Workbench workbench = newWorkbench();
        when(repository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        WorkbenchId missingId = WorkbenchId.of("workbench-missing");
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        // When
        WorkbenchLifecycleResult result = service.load(OWNER, WORKBENCH_ID);

        // Then
        assertEquals(WORKBENCH_ID.getValue(), result.getWorkbenchId());
        assertEquals(WorkbenchStatus.ACTIVE, result.getStatus());
        assertEquals(0L, result.getVersion());
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.load(FOREIGN, WORKBENCH_ID));
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.load(OWNER, missingId));
        verify(repository, never()).update(workbench);
    }

    @Test
    void completePhaseShouldDelegateToAggregateAndPersistNewVersionOnce() {
        // Given
        Workbench workbench = inProgressWorkbench(false);
        when(repository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        assertEquals(3L, workbench.getVersion());

        // When
        WorkbenchPhaseLifecycleResult result = service.completePhase(
                OWNER, WORKBENCH_ID, PHASE, 3L);

        // Then
        assertTrue(result.isChanged());
        assertEquals(WorkbenchPhaseStatus.HUMAN_COMPLETED, result.getPhaseStatus());
        assertEquals(4L, result.getWorkbenchVersion());
        ArgumentCaptor<Workbench> saved = ArgumentCaptor.forClass(Workbench.class);
        verify(repository).update(saved.capture());
        assertEquals(4L, saved.getValue().getVersion());
        assertEquals(WorkbenchPhaseStatus.HUMAN_COMPLETED,
                saved.getValue().phase(PHASE).getStatus());
    }

    @Test
    void reopenPhaseShouldDelegateToAggregateAndReturnCurrentConversationGeneration() {
        // Given
        Workbench workbench = completedWorkbench();
        when(repository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        assertEquals(4L, workbench.getVersion());

        // When
        WorkbenchPhaseLifecycleResult result = service.reopenPhase(
                OWNER, WORKBENCH_ID, PHASE, 4L);

        // Then
        assertTrue(result.isChanged());
        assertEquals(WorkbenchPhaseStatus.IN_PROGRESS, result.getPhaseStatus());
        assertEquals("conversation-0", result.getConversationId());
        assertEquals(0, result.getConversationGeneration());
        assertEquals(5L, result.getWorkbenchVersion());
        verify(repository).update(workbench);
    }

    @Test
    void archiveShouldPersistFirstMutationAndSkipIdempotentReplay() {
        // Given
        Workbench workbench = newWorkbench();
        when(repository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));

        // When
        WorkbenchLifecycleResult archived = service.archive(
                OWNER, WORKBENCH_ID, 0L);
        WorkbenchLifecycleResult replayed = service.archive(
                OWNER, WORKBENCH_ID, 1L);

        // Then
        assertTrue(archived.isChanged());
        assertEquals(WorkbenchStatus.ARCHIVED, archived.getStatus());
        assertEquals(1L, archived.getVersion());
        assertFalse(replayed.isChanged());
        assertEquals(1L, replayed.getVersion());
        verify(repository, times(1)).update(workbench);
    }

    @Test
    void bindConversationShouldPersistFirstBindingAndSkipSameConversationReplay() {
        // Given
        Workbench workbench = newWorkbench();
        when(repository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));

        // When
        WorkbenchPhaseLifecycleResult bound = service.bindConversation(
                OWNER, WORKBENCH_ID, PHASE, "conversation-0", 0L);
        WorkbenchPhaseLifecycleResult replayed = service.bindConversation(
                OWNER, WORKBENCH_ID, PHASE, "conversation-0", 1L);

        // Then
        assertTrue(bound.isChanged());
        assertEquals("conversation-0", bound.getConversationId());
        assertEquals(0, bound.getConversationGeneration());
        assertEquals(1L, bound.getWorkbenchVersion());
        assertFalse(replayed.isChanged());
        assertEquals(1L, replayed.getWorkbenchVersion());
        verify(repository, times(1)).update(workbench);
    }

    @Test
    void restartConversationShouldRetireOldGenerationAndPersistAggregateOnce() {
        // Given
        Workbench workbench = inProgressWorkbench(false);
        when(repository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));

        // When
        WorkbenchPhaseLifecycleResult result = service.restartConversation(
                OWNER, WORKBENCH_ID, PHASE, "conversation-1", 3L);

        // Then
        assertTrue(result.isChanged());
        assertEquals("conversation-1", result.getConversationId());
        assertEquals(1, result.getConversationGeneration());
        assertEquals(4L, result.getWorkbenchVersion());
        assertEquals(2, workbench.phase(PHASE).getConversationHistory().size());
        assertFalse(workbench.phase(PHASE).getConversationHistory().get(0).isActive());
        assertTrue(workbench.phase(PHASE).getConversationHistory().get(1).isActive());
        verify(repository).update(workbench);
    }

    @Test
    void staleExpectedVersionShouldFailBeforeAggregateMutationOrPersistence() {
        // Given
        Workbench workbench = newWorkbench();
        when(repository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));

        // When
        WorkbenchDomainException error = assertThrows(WorkbenchDomainException.class,
                () -> service.bindConversation(
                        OWNER, WORKBENCH_ID, PHASE, "conversation-stale", 1L));

        // Then
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, error.getCode());
        assertEquals(0L, workbench.getVersion());
        assertTrue(workbench.phase(PHASE).getConversationHistory().isEmpty());
        verify(repository, never()).update(workbench);
    }

    @Test
    void domainRejectionShouldPropagateWithoutPersistenceAndOwnerShouldRemainObscured() {
        // Given
        Workbench activeRun = inProgressWorkbench(true);
        when(repository.findById(WORKBENCH_ID)).thenReturn(Optional.of(activeRun));

        // When
        WorkbenchDomainException restartError = assertThrows(WorkbenchDomainException.class,
                () -> service.restartConversation(
                        OWNER, WORKBENCH_ID, PHASE, "conversation-1", 2L));

        // Then
        assertEquals(WorkbenchErrorCode.PHASE_RESTART_INVALID, restartError.getCode());
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.completePhase(
                        FOREIGN, WORKBENCH_ID, PHASE, activeRun.getVersion()));
        verify(repository, never()).update(activeRun);
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
                WORKBENCH_ID, OWNER, "Workbench Lifecycle", "编排人工生命周期",
                AgentType.CODEX, "local", scope,
                new WorkspaceSnapshotReference(
                        "snapshot-lifecycle", topology.getTopologyHash(), repeat('2'), 1),
                CREATED_AT);
    }

    private Workbench inProgressWorkbench(boolean keepRunActive) {
        Workbench workbench = newWorkbench();
        workbench.bindConversation(
                PHASE, "conversation-0", OWNER, CREATED_AT.plusSeconds(1L));
        workbench.prepareRun(
                PHASE, "run-1", RunMode.DISCUSS_READ_ONLY,
                OWNER, CREATED_AT.plusSeconds(2L));
        if (!keepRunActive) {
            workbench.finishRun(PHASE, "run-1", CREATED_AT.plusSeconds(3L));
        }
        return workbench;
    }

    private Workbench completedWorkbench() {
        Workbench workbench = inProgressWorkbench(false);
        workbench.completePhase(PHASE, OWNER, CREATED_AT.plusSeconds(4L));
        return workbench;
    }

    private String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
