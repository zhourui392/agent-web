package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Run ID 到安全 WorkbenchRunReference 的完整解析测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class DefaultHandoffRunReferenceResolverTest {

    private WorkbenchRunSnapshotRepository repository;
    private DefaultHandoffRunReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(WorkbenchRunSnapshotRepository.class);
        resolver = new DefaultHandoffRunReferenceResolver(repository);
    }

    @Test
    void shouldResolveEachIdFromImmutableWorkbenchRunSnapshot() {
        WorkbenchRunSnapshot first = mock(WorkbenchRunSnapshot.class);
        WorkbenchRunSnapshot second = mock(WorkbenchRunSnapshot.class);
        when(first.getRunId()).thenReturn("run-1");
        when(first.getWorkbenchId()).thenReturn(WorkbenchId.of("workbench-1"));
        when(first.getPhase()).thenReturn(WorkbenchPhase.REQUIREMENT_ANALYSIS);
        when(second.getRunId()).thenReturn("run-2");
        when(second.getWorkbenchId()).thenReturn(WorkbenchId.of("workbench-1"));
        when(second.getPhase()).thenReturn(WorkbenchPhase.SOLUTION_DESIGN);
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(first));
        when(repository.findByRunId("run-2")).thenReturn(Optional.of(second));

        List<WorkbenchRunReference> result = resolver.requireReferences(
                Arrays.asList("run-1", "run-2"));

        assertEquals(2, result.size());
        assertEquals("run-1", result.get(0).getRunId());
        assertEquals(WorkbenchId.of("workbench-1"),
                result.get(0).getWorkbenchId());
        assertEquals(WorkbenchPhase.REQUIREMENT_ANALYSIS,
                result.get(0).getPhase());
        assertEquals("Run run-1 (REQUIREMENT_ANALYSIS)",
                result.get(0).getSafeSummary());
    }

    @Test
    void missingRunShouldFailTheWholeResolution() {
        when(repository.findByRunId("missing"))
                .thenReturn(Optional.<WorkbenchRunSnapshot>empty());

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> resolver.requireReferences(
                        Collections.singletonList("missing")));

        assertEquals(HandoffApplicationErrorCode.RUN_REFERENCE_INVALID,
                failure.getCode());
    }
}
