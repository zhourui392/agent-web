package com.example.agentweb.app.workbench.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dynamic Stage Run 快速幂等重放和首次提交编排测试。
 *
 * @author alex
 * @since 2026-08-05
 */
@ExtendWith(MockitoExtension.class)
class WorkbenchStageRunAppServiceTest {

    @Mock private WorkbenchStageRunPreparationService preparationService;
    @Mock private WorkbenchStageRunSubmissionCommitter submissionCommitter;

    @InjectMocks
    private WorkbenchStageRunAppService service;

    @Test
    void should_ReplayBeforePreparationDespiteStaleExpectedVersion() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        WorkbenchStageRunSubmissionResult replayed =
                org.mockito.Mockito.mock(
                        WorkbenchStageRunSubmissionResult.class);
        when(submissionCommitter.replayIfPresent(
                WorkbenchStageRunTestFixtures.OWNER, fixture.command()))
                .thenReturn(Optional.of(replayed));

        // When
        WorkbenchStageRunSubmissionResult result = service.submit(
                WorkbenchStageRunTestFixtures.OWNER, fixture.command());

        // Then
        assertEquals(replayed, result);
        verifyNoInteractions(preparationService);
        org.mockito.Mockito.verify(submissionCommitter, never())
                .commit(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void should_PrepareThenCommitWhenReplayIsAbsent() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        WorkbenchStageRunSubmissionResult committed =
                org.mockito.Mockito.mock(
                        WorkbenchStageRunSubmissionResult.class);
        when(submissionCommitter.replayIfPresent(
                WorkbenchStageRunTestFixtures.OWNER, fixture.command()))
                .thenReturn(Optional.empty());
        when(preparationService.prepare(
                WorkbenchStageRunTestFixtures.OWNER, fixture.command()))
                .thenReturn(fixture.prepared());
        when(submissionCommitter.commit(
                WorkbenchStageRunTestFixtures.OWNER, fixture.prepared()))
                .thenReturn(committed);

        // When
        WorkbenchStageRunSubmissionResult result = service.submit(
                WorkbenchStageRunTestFixtures.OWNER, fixture.command());

        // Then
        assertEquals(committed, result);
        InOrder order = inOrder(preparationService, submissionCommitter);
        order.verify(submissionCommitter).replayIfPresent(
                WorkbenchStageRunTestFixtures.OWNER, fixture.command());
        order.verify(preparationService).prepare(
                WorkbenchStageRunTestFixtures.OWNER, fixture.command());
        order.verify(submissionCommitter).commit(
                WorkbenchStageRunTestFixtures.OWNER, fixture.prepared());
    }
}
