package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.app.workbench.capability.port.ActiveRunCapabilityBindingQuery;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Owner 侧 Phase Capability 查询与写操作门面。
 *
 * <p>Mutation 始终返回“下一轮生效”，同时保留当前活动 Run 已冻结的
 * Binding Hash；不会修改或重新解析活动 Run Snapshot。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class PhaseCapabilityOwnerService {

    private final PhaseCapabilityAppService mutationService;
    private final PhaseCapabilityQueryService queryService;
    private final ActiveRunCapabilityBindingQuery activeBindingQuery;
    private final WorkbenchTelemetry telemetry;

    public PhaseCapabilityOwnerService(
            PhaseCapabilityAppService mutationService,
            PhaseCapabilityQueryService queryService,
            ActiveRunCapabilityBindingQuery activeBindingQuery,
            WorkbenchTelemetry telemetry) {
        this.mutationService = Objects.requireNonNull(
                mutationService, "mutationService");
        this.queryService = Objects.requireNonNull(
                queryService, "queryService");
        this.activeBindingQuery = Objects.requireNonNull(
                activeBindingQuery, "activeBindingQuery");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public EffectivePhaseCapabilityView getEffectiveProfile(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase) {
        return queryService.getEffectiveProfile(actor, workbenchId, phase);
    }

    public Optional<PublicPhaseCapabilityOverrideView> getOverride(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase) {
        return queryService.getOverride(actor, workbenchId, phase);
    }

    public PhaseCapabilityMutationView putOverride(
            OwnerReference actor,
            PutPhaseCapabilityOverrideCommand command) {
        try {
            PhaseCapabilityOverrideSaveResult result =
                    mutationService.putOverride(actor, command);
            telemetry.capabilityVersionChanged();
            return mutationView(
                    command.getWorkbenchId(), command.getPhase(),
                    result.getOverride().getVersion());
        } catch (WorkbenchDomainException failure) {
            throw translateMutationFailure(failure);
        }
    }

    public PhaseCapabilityMutationView deleteOverride(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase, long expectedVersion) {
        try {
            mutationService.deleteOverride(
                    actor, new DeletePhaseCapabilityOverrideCommand(
                            workbenchId, phase, expectedVersion));
            telemetry.capabilityVersionChanged();
            return mutationView(workbenchId, phase, 0L);
        } catch (WorkbenchDomainException failure) {
            throw translateMutationFailure(failure);
        }
    }

    private PhaseCapabilityMutationView mutationView(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            long version) {
        String activeBindingHash = activeBindingQuery
                .findActiveBindingHash(workbenchId, phase)
                .orElse(null);
        return PhaseCapabilityMutationView.nextRun(
                version, activeBindingHash);
    }

    private RuntimeException translateMutationFailure(
            WorkbenchDomainException failure) {
        if (failure.getCode() == WorkbenchErrorCode.VERSION_CONFLICT) {
            telemetry.writeConflict();
            return new PhaseCapabilityApplicationException(
                    PhaseCapabilityApplicationErrorCode.VERSION_CONFLICT,
                    "phase capability override version conflict");
        }
        if (failure.getCode() == WorkbenchErrorCode.RUN_MODE_FORBIDDEN) {
            return new PhaseCapabilityApplicationException(
                    PhaseCapabilityApplicationErrorCode.ESCALATION_DENIED,
                    "phase capability override escalation denied");
        }
        return failure;
    }
}
