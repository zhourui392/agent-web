package com.example.agentweb.infra.workbench.context;

import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStageRunPreparationPlan;
import com.example.agentweb.domain.workbench.context.WorkbenchContextManifest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Empty Workbench Context Manifest 查询测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class EmptyWorkbenchContextManifestQueryTest {

    @Test
    void should_ReturnDeterministicEmptyManifestForRequestedWorkbench() {
        // Given
        WorkbenchId workbenchId = WorkbenchId.of("workbench-stage-1");
        WorkbenchStageRunPreparationPlan plan =
                mock(WorkbenchStageRunPreparationPlan.class);
        when(plan.getWorkbenchId()).thenReturn(workbenchId);
        EmptyWorkbenchContextManifestQuery query =
                new EmptyWorkbenchContextManifestQuery();

        // When
        WorkbenchContextManifest manifest = query.load(plan);

        // Then
        assertEquals(workbenchId, manifest.getWorkbenchId());
        assertEquals(0L, manifest.getContextVersion());
        assertTrue(manifest.getDocuments().isEmpty());
        assertEquals("Context version: 0\nNo published documents.",
                manifest.getPromptContent());
        assertTrue(manifest.getContextHash().matches("[a-f0-9]{64}"));
        assertEquals(manifest.getContextHash(), query.load(plan).getContextHash());
    }
}
