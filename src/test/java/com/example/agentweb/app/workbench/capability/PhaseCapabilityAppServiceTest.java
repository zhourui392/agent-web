package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationRepository;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileCatalog;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase Capability Profile 与 Override 的 Owner、领域委托和持久化编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseCapabilityAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T16:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");
    private static final OwnerReference OTHER = OwnerReference.of("user-2", "Other");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");

    private WorkbenchRepository workbenchRepository;
    private PhaseCapabilityConfigurationRepository configurationRepository;
    private PhaseCapabilityProfileCatalog profileCatalog;
    private PhaseCapabilityOverrideResolver overrideResolver;
    private PhaseCapabilityAppService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        configurationRepository = mock(PhaseCapabilityConfigurationRepository.class);
        profileCatalog = mock(PhaseCapabilityProfileCatalog.class);
        overrideResolver = mock(PhaseCapabilityOverrideResolver.class);
        service = new PhaseCapabilityAppService(
                workbenchRepository, configurationRepository,
                profileCatalog, overrideResolver,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void getDefaultProfileShouldOwnerScopeAndDelegateAllFourPhasesToCatalog() {
        Workbench workbench = workbench();
        Map<WorkbenchPhase, PhaseCapabilityProfile> profiles = profiles();
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        for (Map.Entry<WorkbenchPhase, PhaseCapabilityProfile> entry
                : profiles.entrySet()) {
            when(profileCatalog.requireProfile(entry.getKey()))
                    .thenReturn(entry.getValue());
        }

        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            PhaseCapabilityProfile actual = service.getDefaultProfile(
                    OWNER, WORKBENCH_ID, phase);

            assertSame(profiles.get(phase), actual);
            assertEquals(phase, actual.getPhase());
            assertTrue(requireCapability(
                    actual, "platform-safety").isRequired());
            assertFalse(requireCapability(
                    actual, "refactor-assistant").isRequired());
            verify(profileCatalog).requireProfile(phase);
        }
        verify(workbenchRepository, times(4)).findById(WORKBENCH_ID);
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void getDefaultProfileShouldAllowArchivedOwnerButObscureForeignOwner() {
        Workbench archived = workbench();
        archived.archive(OWNER, NOW.minusSeconds(2));
        PhaseCapabilityProfile profile = profile(WorkbenchPhase.REVIEW_REFACTOR);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(archived));
        when(profileCatalog.requireProfile(WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(profile);

        assertSame(profile, service.getDefaultProfile(
                OWNER, WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR));
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.getDefaultProfile(
                        OTHER, WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR));

        verify(profileCatalog, times(1))
                .requireProfile(WorkbenchPhase.REVIEW_REFACTOR);
    }

    @Test
    void getOverrideShouldReturnOwnerScopedSafeProjectionOrEmpty() {
        PhaseCapabilityProfile profile = profile(WorkbenchPhase.REVIEW_REFACTOR);
        PhaseCapabilityConfiguration configuration = configuration(profile);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.of(configuration));

        Optional<PhaseCapabilityOverrideView> found = service.getOverride(
                OWNER, WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR);

        assertTrue(found.isPresent());
        assertEquals(WORKBENCH_ID, found.get().getWorkbenchId());
        assertEquals(WorkbenchPhase.REVIEW_REFACTOR, found.get().getPhase());
        assertEquals(profile.getProfileId(), found.get().getBaseProfileId());
        assertEquals(0L, found.get().getVersion());
        assertTrue(found.get().getAddedOptionalSkillIds()
                .contains("refactor-assistant"));

        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.empty());
        assertFalse(service.getOverride(
                OWNER, WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR).isPresent());
        verifyNoInteractions(profileCatalog, overrideResolver);
    }

    @Test
    void getOverrideShouldObscureForeignOwnerBeforeConfigurationLookup() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        assertThrows(WorkbenchNotFoundException.class,
                () -> service.getOverride(
                        OTHER, WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR));

        verifyNoInteractions(configurationRepository, profileCatalog, overrideResolver);
    }

    @Test
    void createOverrideShouldResolveRawIdsThenCreateDomainConfigurationForNextRun() {
        Workbench workbench = workbench();
        PhaseCapabilityProfile profile = profile(WorkbenchPhase.REVIEW_REFACTOR);
        CapabilityOverrideSelection selection = selection();
        CapabilityOverride resolved = validOverride();
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.empty());
        when(profileCatalog.requireProfile(WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(profile);
        when(overrideResolver.resolve(profile, selection)).thenReturn(resolved);

        PhaseCapabilityOverrideSaveResult result = service.createOverride(
                OWNER, new CreatePhaseCapabilityOverrideCommand(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR, selection));

        assertEquals(CapabilityOverrideEffectiveFrom.NEXT_RUN,
                result.getEffectiveFrom());
        assertEquals(0L, result.getOverride().getVersion());
        assertEquals(profile.getProfileId(),
                result.getOverride().getBaseProfileId());
        assertEquals(profile.getProfileVersion(),
                result.getOverride().getBaseProfileVersion());
        ArgumentCaptor<PhaseCapabilityConfiguration> saved =
                ArgumentCaptor.forClass(PhaseCapabilityConfiguration.class);
        verify(configurationRepository).save(saved.capture());
        assertSame(resolved, saved.getValue().getOverride());
        assertEquals(OWNER, saved.getValue().getUpdatedBy());
        assertEquals(NOW, saved.getValue().getUpdatedAt());
        InOrder order = inOrder(
                workbenchRepository, configurationRepository,
                profileCatalog, overrideResolver);
        order.verify(workbenchRepository).findById(WORKBENCH_ID);
        order.verify(configurationRepository).find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR);
        order.verify(profileCatalog).requireProfile(WorkbenchPhase.REVIEW_REFACTOR);
        order.verify(overrideResolver).resolve(profile, selection);
        order.verify(configurationRepository).save(saved.getValue());
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void putOverrideShouldCreateAtVersionZeroThenUpdateExistingVersion() {
        PhaseCapabilityProfile profile = profile(WorkbenchPhase.REVIEW_REFACTOR);
        CapabilityOverride first = validOverride();
        CapabilityOverride second = CapabilityOverride.of(
                Collections.<String>emptySet(),
                Collections.singleton("optional-linter"),
                Collections.<String>emptySet(),
                Collections.<String>emptySet());
        PutPhaseCapabilityOverrideCommand create =
                new PutPhaseCapabilityOverrideCommand(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR, 0L,
                        Collections.singletonList("refactor-assistant"),
                        Collections.<String>emptyList(), "first");
        PutPhaseCapabilityOverrideCommand update =
                new PutPhaseCapabilityOverrideCommand(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR, 0L,
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(), "second");
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(profileCatalog.requireProfile(WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(profile);
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.empty());
        when(overrideResolver.resolveSelected(
                profile, create.getOptionalSkillIds(),
                create.getOptionalMcpServerIds(), create.getAdditionalRule()))
                .thenReturn(first);

        PhaseCapabilityOverrideSaveResult created =
                service.putOverride(OWNER, create);

        assertEquals(0L, created.getOverride().getVersion());
        ArgumentCaptor<PhaseCapabilityConfiguration> saved =
                ArgumentCaptor.forClass(PhaseCapabilityConfiguration.class);
        verify(configurationRepository).save(saved.capture());
        PhaseCapabilityConfiguration persisted = saved.getValue();
        assertSame(first, persisted.getOverride());

        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.of(persisted));
        when(overrideResolver.resolveSelected(
                profile, update.getOptionalSkillIds(),
                update.getOptionalMcpServerIds(), update.getAdditionalRule()))
                .thenReturn(second);

        PhaseCapabilityOverrideSaveResult updated =
                service.putOverride(OWNER, update);

        assertEquals(1L, updated.getOverride().getVersion());
        assertSame(second, persisted.getOverride());
        verify(configurationRepository, times(2))
                .save(any(PhaseCapabilityConfiguration.class));
    }

    @Test
    void putOverrideShouldRejectNonZeroVersionWhenOverrideDoesNotExist() {
        PutPhaseCapabilityOverrideCommand command =
                new PutPhaseCapabilityOverrideCommand(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR, 2L,
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(), "");
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.empty());

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.putOverride(OWNER, command));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, failure.getCode());
        verifyNoInteractions(profileCatalog, overrideResolver);
        verify(configurationRepository, never())
                .save(any(PhaseCapabilityConfiguration.class));
    }

    @Test
    void createOverrideShouldRejectExistingConfigurationBeforeCatalogResolution() {
        PhaseCapabilityProfile profile = profile(WorkbenchPhase.REVIEW_REFACTOR);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.of(configuration(profile)));

        PhaseCapabilityApplicationException failure = assertThrows(
                PhaseCapabilityApplicationException.class,
                () -> service.createOverride(
                        OWNER, new CreatePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                                selection())));

        assertEquals(PhaseCapabilityApplicationErrorCode.OVERRIDE_ALREADY_EXISTS,
                failure.getCode());
        verifyNoInteractions(profileCatalog, overrideResolver);
        verify(configurationRepository, never())
                .save(any(PhaseCapabilityConfiguration.class));
    }

    @Test
    void createOverrideShouldPreserveDuplicateIdsForTrustedCatalogResolver() {
        PhaseCapabilityProfile profile = profile(WorkbenchPhase.REVIEW_REFACTOR);
        CapabilityOverrideSelection duplicated = new CapabilityOverrideSelection(
                Arrays.asList("refactor-assistant", "refactor-assistant"),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.empty());
        when(profileCatalog.requireProfile(WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(profile);
        when(overrideResolver.resolve(profile, duplicated))
                .thenThrow(new CapabilityCatalogException(
                        "CAPABILITY_ID_DUPLICATE", "duplicate capability id"));

        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class,
                () -> service.createOverride(
                        OWNER, new CreatePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                                duplicated)));

        assertEquals("CAPABILITY_ID_DUPLICATE", failure.getCode());
        assertEquals(2, duplicated.getAddedOptionalSkillIds().size());
        verify(overrideResolver).resolve(profile, duplicated);
        verify(configurationRepository, never())
                .save(any(PhaseCapabilityConfiguration.class));
    }

    @Test
    void createOverrideShouldDelegateMutualExclusionToResolverAndPolicy() {
        PhaseCapabilityProfile profile = profile(WorkbenchPhase.REVIEW_REFACTOR);
        CapabilityOverrideSelection conflicting = new CapabilityOverrideSelection(
                Collections.singletonList("refactor-assistant"),
                Collections.singletonList("refactor-assistant"),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.empty());
        when(profileCatalog.requireProfile(WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(profile);
        when(overrideResolver.resolve(profile, conflicting))
                .thenThrow(new IllegalArgumentException(
                        "the same optional skill cannot be both added and removed"));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOverride(
                        OWNER, new CreatePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                                conflicting)));

        verify(configurationRepository, never())
                .save(any(PhaseCapabilityConfiguration.class));
    }

    @Test
    void createOverrideShouldLetDomainPolicyRejectUntrustedResolvedIds() {
        PhaseCapabilityProfile profile = profile(WorkbenchPhase.REVIEW_REFACTOR);
        CapabilityOverrideSelection selection = selection();
        CapabilityOverride untrusted = CapabilityOverride.of(
                Collections.singleton("untrusted-local-skill"),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet());
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.empty());
        when(profileCatalog.requireProfile(WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(profile);
        when(overrideResolver.resolve(profile, selection)).thenReturn(untrusted);

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.createOverride(
                        OWNER, new CreatePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                                selection)));

        assertEquals(WorkbenchErrorCode.RUN_MODE_FORBIDDEN, failure.getCode());
        verify(configurationRepository, never())
                .save(any(PhaseCapabilityConfiguration.class));
    }

    @Test
    void updateOverrideShouldPassExpectedVersionToAggregateAndSaveForNextRun() {
        PhaseCapabilityProfile profile = profile(WorkbenchPhase.REVIEW_REFACTOR);
        PhaseCapabilityConfiguration configuration = configuration(profile);
        CapabilityOverrideSelection selection = selection();
        CapabilityOverride next = CapabilityOverride.of(
                Collections.<String>emptySet(),
                Collections.singleton("optional-linter"),
                Collections.<String>emptySet(),
                Collections.singleton("review/human-opinion-only"));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.of(configuration));
        when(profileCatalog.requireProfile(WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(profile);
        when(overrideResolver.resolve(profile, selection)).thenReturn(next);

        PhaseCapabilityOverrideSaveResult result = service.updateOverride(
                OWNER, new UpdatePhaseCapabilityOverrideCommand(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                        0L, selection));

        assertEquals(1L, result.getOverride().getVersion());
        assertEquals(CapabilityOverrideEffectiveFrom.NEXT_RUN,
                result.getEffectiveFrom());
        assertSame(next, configuration.getOverride());
        assertEquals(profile.getProfileId(), configuration.getBaseProfileId());
        verify(configurationRepository).save(configuration);
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void updateOverrideShouldDelegateVersionConflictWithoutPartialSave() {
        PhaseCapabilityProfile profile = profile(WorkbenchPhase.REVIEW_REFACTOR);
        PhaseCapabilityConfiguration configuration = configuration(profile);
        CapabilityOverride initial = configuration.getOverride();
        CapabilityOverrideSelection selection = selection();
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.of(configuration));
        when(profileCatalog.requireProfile(WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(profile);
        when(overrideResolver.resolve(profile, selection))
                .thenReturn(CapabilityOverride.empty());

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.updateOverride(
                        OWNER, new UpdatePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                                9L, selection)));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, failure.getCode());
        assertSame(initial, configuration.getOverride());
        assertEquals(0L, configuration.getVersion());
        verify(configurationRepository, never()).save(configuration);
    }

    @Test
    void updateOverrideShouldFailMissingBeforeCatalogResolution() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR))
                .thenReturn(Optional.empty());

        PhaseCapabilityApplicationException failure = assertThrows(
                PhaseCapabilityApplicationException.class,
                () -> service.updateOverride(
                        OWNER, new UpdatePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                                0L, selection())));

        assertEquals(PhaseCapabilityApplicationErrorCode.OVERRIDE_NOT_FOUND,
                failure.getCode());
        verifyNoInteractions(profileCatalog, overrideResolver);
        verify(configurationRepository, never())
                .save(any(PhaseCapabilityConfiguration.class));
    }

    @Test
    void deleteOverrideShouldDelegateExpectedVersionAndReturnNextRunEffect() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        PhaseCapabilityOverrideDeleteResult result = service.deleteOverride(
                OWNER, new DeletePhaseCapabilityOverrideCommand(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR, 7L));

        assertEquals(WORKBENCH_ID, result.getWorkbenchId());
        assertEquals(WorkbenchPhase.REVIEW_REFACTOR, result.getPhase());
        assertEquals(CapabilityOverrideEffectiveFrom.NEXT_RUN,
                result.getEffectiveFrom());
        verify(configurationRepository).delete(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR, 7L);
        verifyNoInteractions(profileCatalog, overrideResolver);
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void everyWriteShouldObscureForeignOwnerBeforeCapabilityAccess() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        assertThrows(WorkbenchNotFoundException.class,
                () -> service.createOverride(
                        OTHER, new CreatePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                                selection())));
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.updateOverride(
                        OTHER, new UpdatePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                                0L, selection())));
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.deleteOverride(
                        OTHER, new DeletePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR, 0L)));

        verifyNoInteractions(configurationRepository, profileCatalog, overrideResolver);
    }

    @Test
    void everyWriteShouldDelegateArchivedGuardBeforeCapabilityAccess() {
        Workbench archived = workbench();
        archived.archive(OWNER, NOW.minusSeconds(2));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(archived));

        WorkbenchDomainException createFailure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.createOverride(
                        OWNER, new CreatePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                                selection())));
        WorkbenchDomainException updateFailure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.updateOverride(
                        OWNER, new UpdatePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                                0L, selection())));
        WorkbenchDomainException deleteFailure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.deleteOverride(
                        OWNER, new DeletePhaseCapabilityOverrideCommand(
                                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR, 0L)));

        assertEquals(WorkbenchErrorCode.ARCHIVED, createFailure.getCode());
        assertEquals(WorkbenchErrorCode.ARCHIVED, updateFailure.getCode());
        assertEquals(WorkbenchErrorCode.ARCHIVED, deleteFailure.getCode());
        verifyNoInteractions(configurationRepository, profileCatalog, overrideResolver);
    }

    @Test
    void versionedCommandsShouldRejectNegativeExpectedVersionAtBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> new UpdatePhaseCapabilityOverrideCommand(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                        -1L, selection()));
        assertThrows(IllegalArgumentException.class,
                () -> new DeletePhaseCapabilityOverrideCommand(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR, -1L));
    }

    private static PhaseCapabilityConfiguration configuration(
            PhaseCapabilityProfile profile) {
        return PhaseCapabilityConfiguration.create(
                WORKBENCH_ID, profile.getPhase(),
                profile.getProfileId(), profile.getProfileVersion(),
                validOverride(), profile.getOverridePolicy(),
                OWNER, NOW.minusSeconds(1));
    }

    private static CapabilityOverride validOverride() {
        return CapabilityOverride.of(
                Collections.singleton("refactor-assistant"),
                Collections.singleton("optional-linter"),
                Collections.singleton("repository-query"),
                Collections.singleton("review/explain-structure"));
    }

    private static CapabilityOverrideSelection selection() {
        return new CapabilityOverrideSelection(
                Collections.singletonList("refactor-assistant"),
                Collections.singletonList("optional-linter"),
                Collections.singletonList("repository-query"),
                Collections.singletonList("review/explain-structure"));
    }

    private static Map<WorkbenchPhase, PhaseCapabilityProfile> profiles() {
        Map<WorkbenchPhase, PhaseCapabilityProfile> result =
                new EnumMap<WorkbenchPhase, PhaseCapabilityProfile>(
                        WorkbenchPhase.class);
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            result.put(phase, profile(phase));
        }
        return result;
    }

    private static PhaseCapabilityProfile profile(WorkbenchPhase phase) {
        List<PhaseCapabilityReference> capabilities = Arrays.asList(
                new PhaseCapabilityReference(
                        "platform-safety", PhaseCapabilityType.RULE, true),
                new PhaseCapabilityReference(
                        "refactor-assistant", PhaseCapabilityType.SKILL, false),
                new PhaseCapabilityReference(
                        "optional-linter", PhaseCapabilityType.SKILL, false),
                new PhaseCapabilityReference(
                        "repository-query", PhaseCapabilityType.MCP_SERVER, false),
                new PhaseCapabilityReference(
                        "review/explain-structure", PhaseCapabilityType.RULE, false),
                new PhaseCapabilityReference(
                        "review/human-opinion-only", PhaseCapabilityType.RULE, false));
        return PhaseCapabilityProfile.create(
                "workbench-" + phase.name().toLowerCase(java.util.Locale.ROOT),
                "1", phase, capabilities);
    }

    private static PhaseCapabilityReference requireCapability(
            PhaseCapabilityProfile profile, String capabilityId) {
        for (PhaseCapabilityReference capability : profile.getCapabilities()) {
            if (capability.getId().equals(capabilityId)) {
                return capability;
            }
        }
        throw new AssertionError("missing capability: " + capabilityId);
    }

    private static Workbench workbench() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web", repeat('1'), false)),
                50);
        WorkspaceSnapshotReference snapshotReference = new WorkspaceSnapshotReference(
                "snapshot-1",
                com.example.agentweb.domain.workspace.WorkspaceTopology.of(
                        "/workspace", selection).getTopologyHash(),
                repeat('2'), 1);
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "Configure capabilities",
                AgentType.CODEX, "local", scope, snapshotReference,
                NOW.minusSeconds(10));
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }
}
