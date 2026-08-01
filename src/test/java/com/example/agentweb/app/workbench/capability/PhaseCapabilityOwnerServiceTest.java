package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.app.workbench.capability.port.ActiveRunCapabilityBindingQuery;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Capability Owner facade 的 mutation evidence 与稳定错误转换测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseCapabilityOwnerServiceTest {

    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final WorkbenchPhase PHASE = WorkbenchPhase.IMPLEMENT_TEST;

    private PhaseCapabilityAppService mutationService;
    private PhaseCapabilityQueryService queryService;
    private ActiveRunCapabilityBindingQuery activeBindingQuery;
    private WorkbenchTelemetry telemetry;
    private PhaseCapabilityOwnerService service;

    @BeforeEach
    void setUp() {
        mutationService = mock(PhaseCapabilityAppService.class);
        queryService = mock(PhaseCapabilityQueryService.class);
        activeBindingQuery = mock(ActiveRunCapabilityBindingQuery.class);
        telemetry = mock(WorkbenchTelemetry.class);
        service = new PhaseCapabilityOwnerService(
                mutationService, queryService, activeBindingQuery, telemetry);
    }

    @Test
    void putShouldReturnNextRunVersionAndCurrentActiveBindingEvidence() {
        PutPhaseCapabilityOverrideCommand command = command(2L);
        PhaseCapabilityOverrideSaveResult saved =
                mock(PhaseCapabilityOverrideSaveResult.class);
        PhaseCapabilityOverrideView override =
                mock(PhaseCapabilityOverrideView.class);
        when(saved.getOverride()).thenReturn(override);
        when(override.getVersion()).thenReturn(3L);
        when(mutationService.putOverride(OWNER, command)).thenReturn(saved);
        String activeHash = "a".repeat(64);
        when(activeBindingQuery.findActiveBindingHash(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.of(activeHash));

        PhaseCapabilityMutationView result = service.putOverride(
                OWNER, command);

        assertEquals(3L, result.getVersion());
        assertEquals("NEXT_RUN", result.getEffectiveFrom());
        assertEquals(activeHash, result.getActiveRunSnapshotHash());
        verify(telemetry).capabilityVersionChanged();
    }

    @Test
    void deleteShouldReturnTombstoneVersionWithoutChangingActiveBindingEvidence() {
        String activeHash = "b".repeat(64);
        PhaseCapabilityOverrideDeleteResult deleted =
                PhaseCapabilityOverrideDeleteResult.restoredDefault(
                        WORKBENCH_ID, PHASE, 5L);
        when(mutationService.deleteOverride(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.any(
                        DeletePhaseCapabilityOverrideCommand.class)))
                .thenReturn(deleted);
        when(activeBindingQuery.findActiveBindingHash(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.of(activeHash));

        PhaseCapabilityMutationView result = service.deleteOverride(
                OWNER, WORKBENCH_ID, PHASE, 4L);

        assertEquals(5L, result.getVersion());
        assertEquals(activeHash, result.getActiveRunSnapshotHash());
        ArgumentCaptor<DeletePhaseCapabilityOverrideCommand> commandCaptor =
                ArgumentCaptor.forClass(
                        DeletePhaseCapabilityOverrideCommand.class);
        verify(mutationService).deleteOverride(
                org.mockito.ArgumentMatchers.eq(OWNER),
                commandCaptor.capture());
        assertEquals(WORKBENCH_ID,
                commandCaptor.getValue().getWorkbenchId());
        assertEquals(PHASE, commandCaptor.getValue().getPhase());
        assertEquals(4L, commandCaptor.getValue().getExpectedVersion());
        verify(telemetry).capabilityVersionChanged();
    }

    @Test
    void publicMutationShouldTranslateVersionAndEscalationFailures() {
        PutPhaseCapabilityOverrideCommand versionCommand = command(1L);
        PutPhaseCapabilityOverrideCommand escalationCommand = command(2L);
        when(mutationService.putOverride(OWNER, versionCommand))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.VERSION_CONFLICT, "stale"));
        when(mutationService.putOverride(OWNER, escalationCommand))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.RUN_MODE_FORBIDDEN, "denied"));

        PhaseCapabilityApplicationException version = assertThrows(
                PhaseCapabilityApplicationException.class,
                () -> service.putOverride(OWNER, versionCommand));
        PhaseCapabilityApplicationException escalation = assertThrows(
                PhaseCapabilityApplicationException.class,
                () -> service.putOverride(OWNER, escalationCommand));

        assertEquals(PhaseCapabilityApplicationErrorCode.VERSION_CONFLICT,
                version.getCode());
        assertEquals(PhaseCapabilityApplicationErrorCode.ESCALATION_DENIED,
                escalation.getCode());
        verify(telemetry).writeConflict();
    }

    private static PutPhaseCapabilityOverrideCommand command(long version) {
        return new PutPhaseCapabilityOverrideCommand(
                WORKBENCH_ID, PHASE, version,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(), "");
    }
}
