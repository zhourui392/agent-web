package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Admin Run 控制边界的 exact Workbench/Phase binding 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class AdminWorkbenchRunAccessResolverTest {

    private WorkbenchRepository workbenchRepository;
    private WorkbenchRunSnapshotRepository snapshotRepository;
    private ChatRunRepository runRepository;
    private AdminWorkbenchRunAccessResolver resolver;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        snapshotRepository = mock(WorkbenchRunSnapshotRepository.class);
        runRepository = mock(ChatRunRepository.class);
        resolver = new AdminWorkbenchRunAccessResolver(
                workbenchRepository, snapshotRepository, runRepository);
    }

    @Test
    void shouldResolveExactRunWithoutReceivingOwnerReference() {
        ChatRun run = AdminWorkbenchRunTestFixtures.runningRun();
        stubExact(run);

        AdminControlledWorkbenchRun controlled = resolver.requireExact(
                AdminWorkbenchRunTestFixtures.WORKBENCH_ID, "run-1");

        assertEquals(run, controlled.getRun());
        assertEquals(AdminWorkbenchRunTestFixtures.WORKBENCH_ID,
                controlled.getWorkbench().getId());
    }

    @Test
    void shouldObscureCrossWorkbenchRunBindingAsNotFound() {
        ChatRun mismatched = ChatRun.submit(
                ChatRunId.of("run-1"), "session-1", 1L, "submission-1",
                false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "another-workbench:REQUIREMENT_ANALYSIS", "run-1"),
                AdminWorkbenchRunTestFixtures.NOW.minusSeconds(2));
        stubExact(mismatched);

        assertThrows(AdminWorkbenchRunNotFoundException.class,
                () -> resolver.requireExact(
                        AdminWorkbenchRunTestFixtures.WORKBENCH_ID,
                        "run-1"));
    }

    private void stubExact(ChatRun run) {
        when(workbenchRepository.findById(
                AdminWorkbenchRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(
                        AdminWorkbenchRunTestFixtures.workbench()));
        when(snapshotRepository.findByRunId("run-1"))
                .thenReturn(Optional.of(
                        AdminWorkbenchRunTestFixtures.snapshot()));
        when(runRepository.findById(ChatRunId.of("run-1")))
                .thenReturn(Optional.of(run));
    }
}
