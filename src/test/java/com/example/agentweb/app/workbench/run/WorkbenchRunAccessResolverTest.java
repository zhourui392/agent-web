package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Owner-first、malformed Run fail-closed 授权顺序测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunAccessResolverTest {

    private WorkbenchRepository workbenchRepository;
    private WorkbenchRunSnapshotRepository snapshotRepository;
    private ChatRunRepository runRepository;
    private WorkbenchRunAccessResolver resolver;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        snapshotRepository = mock(WorkbenchRunSnapshotRepository.class);
        runRepository = mock(ChatRunRepository.class);
        resolver = new WorkbenchRunAccessResolver(
                workbenchRepository, snapshotRepository, runRepository);
    }

    @Test
    void foreignOwnerShouldFailBeforeRunLookup() {
        Workbench workbench = WorkbenchRunTestFixtures.workbench();
        when(workbenchRepository.findById(
                WorkbenchRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));

        assertThrows(WorkbenchRunNotFoundException.class,
                () -> resolver.requireAuthorized(
                        OwnerReference.of("owner-2", "Mallory"),
                        WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1"));

        verifyNoInteractions(snapshotRepository, runRepository);
    }

    @Test
    void malformedRunShouldFailClosedAfterOwnerValidation() {
        Workbench workbench = WorkbenchRunTestFixtures.workbench();
        when(workbenchRepository.findById(
                WorkbenchRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));

        assertThrows(WorkbenchRunNotFoundException.class,
                () -> resolver.requireAuthorized(
                        WorkbenchRunTestFixtures.OWNER,
                        WorkbenchRunTestFixtures.WORKBENCH_ID, "   "));

        verifyNoInteractions(snapshotRepository, runRepository);
    }
}
