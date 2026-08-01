package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.capability.port.ActiveRunCapabilityBindingQuery;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationRepository;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreview;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreviewResolver;
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

import java.util.Objects;
import java.util.Optional;

/**
 * Effective Profile 与公开 Override 的 Owner-scoped CQRS 编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
@Transactional(readOnly = true)
public class PhaseCapabilityQueryService {

    private final WorkbenchRepository workbenchRepository;
    private final PhaseCapabilityConfigurationRepository configurationRepository;
    private final PhaseCapabilityProfileCatalog profileCatalog;
    private final PhaseCapabilityPreviewResolver previewResolver;
    private final ActiveRunCapabilityBindingQuery activeRunBindingQuery;

    public PhaseCapabilityQueryService(
            WorkbenchRepository workbenchRepository,
            PhaseCapabilityConfigurationRepository configurationRepository,
            PhaseCapabilityProfileCatalog profileCatalog,
            PhaseCapabilityPreviewResolver previewResolver,
            ActiveRunCapabilityBindingQuery activeRunBindingQuery) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.configurationRepository = Objects.requireNonNull(
                configurationRepository, "configurationRepository");
        this.profileCatalog = Objects.requireNonNull(
                profileCatalog, "profileCatalog");
        this.previewResolver = Objects.requireNonNull(
                previewResolver, "previewResolver");
        this.activeRunBindingQuery = Objects.requireNonNull(
                activeRunBindingQuery, "activeRunBindingQuery");
    }

    public EffectivePhaseCapabilityView getEffectiveProfile(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        Optional<PhaseCapabilityConfiguration> configuration =
                configurationRepository.find(workbenchId, phase);
        CapabilityOverride override = configuration.isPresent()
                ? configuration.get().getOverride() : CapabilityOverride.empty();
        long overrideVersion = configuration.isPresent()
                ? configuration.get().getVersion() : 0L;
        PhaseCapabilityProfile profile = profileCatalog.requireProfile(phase);
        PhaseCapabilityPreview preview = previewResolver.resolve(
                profile, override, workbench.getAgentType());
        String activeBindingHash = activeRunBindingQuery
                .findActiveBindingHash(workbenchId, phase).orElse(null);
        return EffectivePhaseCapabilityView.from(
                preview, override, overrideVersion, activeBindingHash);
    }

    public Optional<PublicPhaseCapabilityOverrideView> getOverride(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        Optional<PhaseCapabilityConfiguration> configuration =
                configurationRepository.find(workbenchId, phase);
        if (!configuration.isPresent()) {
            return Optional.empty();
        }
        PhaseCapabilityProfile profile = profileCatalog.requireProfile(phase);
        PhaseCapabilityPreview preview = previewResolver.resolve(
                profile, configuration.get().getOverride(),
                workbench.getAgentType());
        return Optional.of(PublicPhaseCapabilityOverrideView.from(
                configuration.get(), preview));
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workbenchId, "workbenchId");
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
        try {
            workbench.requireOwnedBy(actor);
            return workbench;
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
                throw new WorkbenchNotFoundException();
            }
            throw failure;
        }
    }
}
