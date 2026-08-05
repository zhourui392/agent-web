package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.stage.WorkbenchStageCommandQueryService;
import com.example.agentweb.app.workbench.stage.WorkbenchStageCommandView;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCapabilityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workbench Stage 冻结 Command 查询应用服务测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageCommandQueryServiceTest {

    private WorkbenchRepository workbenchRepository;
    private WorkbenchStageCapabilityResolver capabilityResolver;
    private WorkbenchStageCommandQueryService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        capabilityResolver = mock(WorkbenchStageCapabilityResolver.class);
        service = new WorkbenchStageCommandQueryService(
                workbenchRepository, capabilityResolver);
    }

    @Test
    void should_ListOnlyFrozenStageCommands_When_OwnerMatches() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        CommandDefinition command = CommandDefinition.create(
                "architecture-review", "1.0.0",
                "Architecture Review", "Review architecture", "<module>",
                "Review $ARGUMENTS", "platform-commands",
                WorkbenchStageRunTestFixtures.NOW);
        when(workbenchRepository.findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(fixture.workbench()));
        when(capabilityResolver.listCommands(
                fixture.workbench().stage(
                        WorkbenchStageRunTestFixtures
                                .STAGE_INSTANCE_IDENTIFIER)
                        .getSnapshot()))
                .thenReturn(Collections.singletonList(command));

        // When
        List<WorkbenchStageCommandView> views = service.list(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER);

        // Then
        assertEquals(1, views.size());
        assertEquals("architecture-review", views.get(0).getIdentifier());
        assertEquals("Architecture Review", views.get(0).getDisplayName());
        assertEquals("<module>", views.get(0).getArgumentHint());
    }

    @Test
    void should_HideWorkbenchBeforeResolvingCommands_When_OwnerDiffers() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        when(workbenchRepository.findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(fixture.workbench()));

        // When / Then
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.list(
                        OwnerReference.of("other-owner", "Other"),
                        WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                        WorkbenchStageRunTestFixtures
                                .STAGE_INSTANCE_IDENTIFIER));
        verifyNoInteractions(capabilityResolver);
    }
}
