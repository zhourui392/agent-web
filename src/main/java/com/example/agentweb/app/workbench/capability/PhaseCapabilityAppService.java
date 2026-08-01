package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationRepository;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationState;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileCatalog;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 默认 Capability Profile 与高级 Override 的事务编排。
 *
 * <p>可信 Catalog ID、required/optional、重复和互斥交给 Resolver/Profile Policy；
 * Override 版本交给 PhaseCapabilityConfiguration 或 Repository。服务不修改活动 Run Snapshot。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Transactional(readOnly = true)
@Service
public class PhaseCapabilityAppService {

    private final WorkbenchRepository workbenchRepository;
    private final PhaseCapabilityConfigurationRepository configurationRepository;
    private final PhaseCapabilityProfileCatalog profileCatalog;
    private final PhaseCapabilityOverrideResolver overrideResolver;
    private final Clock clock;

    public PhaseCapabilityAppService(
            WorkbenchRepository workbenchRepository,
            PhaseCapabilityConfigurationRepository configurationRepository,
            PhaseCapabilityProfileCatalog profileCatalog,
            PhaseCapabilityOverrideResolver overrideResolver,
            Clock clock) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.configurationRepository = Objects.requireNonNull(
                configurationRepository, "configurationRepository");
        this.profileCatalog = Objects.requireNonNull(
                profileCatalog, "profileCatalog");
        this.overrideResolver = Objects.requireNonNull(
                overrideResolver, "overrideResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PhaseCapabilityProfile getDefaultProfile(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase) {
        requireOwnedWorkbench(actor, workbenchId);
        return profileCatalog.requireProfile(phase);
    }

    public Optional<PhaseCapabilityOverrideView> getOverride(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase) {
        requireOwnedWorkbench(actor, workbenchId);
        return configurationRepository.find(workbenchId, phase)
                .map(PhaseCapabilityOverrideView::from);
    }

    @Transactional
    public PhaseCapabilityOverrideSaveResult createOverride(
            OwnerReference actor,
            CreatePhaseCapabilityOverrideCommand command) {
        Objects.requireNonNull(command, "command");
        requireOperableWorkbench(actor, command.getWorkbenchId());
        PhaseCapabilityConfigurationState state =
                configurationRepository.findState(
                        command.getWorkbenchId(), command.getPhase());
        state.requireCanCreate();
        PhaseCapabilityProfile profile =
                profileCatalog.requireProfile(command.getPhase());
        CapabilityOverride override = overrideResolver.resolve(
                profile, command.getSelection());
        PhaseCapabilityConfiguration configuration =
                state.createOverride(profile, override, actor, clock.instant());
        configurationRepository.save(configuration);
        return PhaseCapabilityOverrideSaveResult.saved(configuration);
    }

    @Transactional
    public PhaseCapabilityOverrideSaveResult updateOverride(
            OwnerReference actor,
            UpdatePhaseCapabilityOverrideCommand command) {
        Objects.requireNonNull(command, "command");
        requireOperableWorkbench(actor, command.getWorkbenchId());
        PhaseCapabilityConfigurationState state =
                configurationRepository.findState(
                        command.getWorkbenchId(), command.getPhase());
        state.requireCanUpdate(command.getExpectedVersion());
        PhaseCapabilityProfile profile =
                profileCatalog.requireProfile(command.getPhase());
        CapabilityOverride override = overrideResolver.resolve(
                profile, command.getSelection());
        PhaseCapabilityConfiguration configuration = state.updateOverride(
                command.getExpectedVersion(), profile, override, actor,
                clock.instant());
        configurationRepository.save(configuration);
        return PhaseCapabilityOverrideSaveResult.saved(configuration);
    }

    @Transactional
    public PhaseCapabilityOverrideSaveResult putOverride(
            OwnerReference actor, PutPhaseCapabilityOverrideCommand command) {
        Objects.requireNonNull(command, "command");
        requireOperableWorkbench(actor, command.getWorkbenchId());
        PhaseCapabilityConfigurationState state =
                configurationRepository.findState(
                        command.getWorkbenchId(), command.getPhase());
        state.requireCanPut(command.getExpectedVersion());
        PhaseCapabilityProfile profile =
                profileCatalog.requireProfile(command.getPhase());
        CapabilityOverride override = overrideResolver.resolveSelected(
                profile, command.getOptionalSkillIds(),
                command.getOptionalMcpServerIds(),
                command.getAdditionalRule());
        PhaseCapabilityConfiguration configuration = state.putOverride(
                command.getExpectedVersion(), profile, override, actor,
                clock.instant());
        configurationRepository.save(configuration);
        return PhaseCapabilityOverrideSaveResult.saved(configuration);
    }

    @Transactional
    public PhaseCapabilityOverrideDeleteResult deleteOverride(
            OwnerReference actor,
            DeletePhaseCapabilityOverrideCommand command) {
        Objects.requireNonNull(command, "command");
        requireOperableWorkbench(actor, command.getWorkbenchId());
        long nextVersion = configurationRepository.delete(
                command.getWorkbenchId(), command.getPhase(),
                command.getExpectedVersion());
        return PhaseCapabilityOverrideDeleteResult.restoredDefault(
                command.getWorkbenchId(), command.getPhase(), nextVersion);
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Workbench workbench = findWorkbench(actor, workbenchId);
        try {
            workbench.requireOwnedBy(actor);
            return workbench;
        } catch (WorkbenchDomainException ex) {
            throw obscureOwnerFailure(ex);
        }
    }

    private Workbench requireOperableWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Workbench workbench = findWorkbench(actor, workbenchId);
        try {
            workbench.requireOperableBy(actor);
            return workbench;
        } catch (WorkbenchDomainException ex) {
            throw obscureOwnerFailure(ex);
        }
    }

    private Workbench findWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workbenchId, "workbenchId");
        return workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
    }

    private RuntimeException obscureOwnerFailure(WorkbenchDomainException failure) {
        if (failure.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
            return new WorkbenchNotFoundException();
        }
        return failure;
    }

}
