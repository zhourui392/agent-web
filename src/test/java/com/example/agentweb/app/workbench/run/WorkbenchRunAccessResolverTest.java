package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshotRepository;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dynamic Stage Owner-first、malformed Run fail-closed 授权顺序测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchRunAccessResolverTest {

    private WorkbenchRepository workbenchRepository;
    private WorkbenchStageRunSnapshotRepository snapshotRepository;
    private ChatRunRepository runRepository;
    private WorkbenchRunAccessResolver resolver;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        snapshotRepository = mock(
                WorkbenchStageRunSnapshotRepository.class);
        runRepository = mock(ChatRunRepository.class);
        resolver = new WorkbenchRunAccessResolver(
                workbenchRepository, snapshotRepository, runRepository);
    }

    @Test
    void should_FailBeforeRunLookup_When_ActorIsNotWorkbenchOwner() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        when(workbenchRepository.findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(fixture.workbench()));

        // When / Then
        assertThrows(WorkbenchRunNotFoundException.class,
                () -> resolver.requireAuthorized(
                        OwnerReference.of("owner-2", "Mallory"),
                        WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                        WorkbenchStageRunTestFixtures.RUN_IDENTIFIER));
        verifyNoInteractions(snapshotRepository, runRepository);
    }

    @Test
    void should_FailAfterOwnerValidation_When_RunIdentifierIsMalformed() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        when(workbenchRepository.findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(fixture.workbench()));

        // When / Then
        assertThrows(WorkbenchRunNotFoundException.class,
                () -> resolver.requireAuthorized(
                        WorkbenchStageRunTestFixtures.OWNER,
                        WorkbenchStageRunTestFixtures.WORKBENCH_ID, "   "));
        verifyNoInteractions(snapshotRepository, runRepository);
    }

    @Test
    void should_AuthorizeStageRun_When_ExactBindingMatches() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        ChatRun run = dynamicStageRun(
                WorkbenchStageRunTestFixtures.contextIdentifier());
        when(workbenchRepository.findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(fixture.workbench()));
        when(snapshotRepository.findByRunId(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER))
                .thenReturn(Optional.of(fixture.snapshot()));
        when(runRepository.findById(ChatRunId.of(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER)))
                .thenReturn(Optional.of(run));

        // When
        AuthorizedWorkbenchRun authorized = resolver.requireAuthorized(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER);

        // Then
        assertSame(fixture.workbench(), authorized.getWorkbench());
        assertSame(run, authorized.getRun());
        assertSame(fixture.snapshot(), authorized.getSnapshot());
        assertEquals(WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER,
                authorized.getSnapshot().getStageInstanceIdentifier());
    }

    @Test
    void should_HideWrongStageOrigin_When_SnapshotExists() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        ChatRun run = dynamicStageRun(
                fixture.workbench().getId().getValue() + ":wrong-stage");
        when(workbenchRepository.findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(fixture.workbench()));
        when(snapshotRepository.findByRunId(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER))
                .thenReturn(Optional.of(fixture.snapshot()));
        when(runRepository.findById(ChatRunId.of(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER)))
                .thenReturn(Optional.of(run));

        // When / Then
        assertThrows(WorkbenchRunNotFoundException.class,
                () -> resolver.requireAuthorized(
                        WorkbenchStageRunTestFixtures.OWNER,
                        WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                        WorkbenchStageRunTestFixtures.RUN_IDENTIFIER));
    }

    private static ChatRun dynamicStageRun(String originReference) {
        return ChatRun.submit(
                ChatRunId.of(WorkbenchStageRunTestFixtures.RUN_IDENTIFIER),
                WorkbenchStageRunTestFixtures.SESSION_IDENTIFIER, 1L,
                "submit-stage-run", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        originReference,
                        WorkbenchStageRunTestFixtures.RUN_IDENTIFIER),
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(2));
    }
}
