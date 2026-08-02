package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.capability.port.ActiveRunCapabilityBindingQuery;
import com.example.agentweb.app.workbench.port.WorkspaceDevelopmentContextGateway;
import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityRequest;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.capability.RuleCatalog;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.capability.SkillDependency;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.AdditionalCapabilityRule;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationRepository;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationState;
import com.example.agentweb.domain.workbench.PhaseCapabilityBindingResolver;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreview;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreviewResolver;
import com.example.agentweb.domain.workbench.PhaseCapabilityOverrideResolution;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileCatalog;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityResolutionPolicy;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentContextClassifier;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentMarker;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkspaceDevelopmentContext;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Effective Capability Profile/Override 的 Owner 查询编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseCapabilityQueryServiceTest {

    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference OTHER =
            OwnerReference.of("owner-2", "Other");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final WorkbenchPhase PHASE = WorkbenchPhase.IMPLEMENT_TEST;
    private static final Instant NOW = Instant.parse("2026-08-01T16:00:00Z");

    private WorkbenchRepository workbenchRepository;
    private PhaseCapabilityConfigurationRepository configurationRepository;
    private PhaseCapabilityProfileCatalog profileCatalog;
    private PhaseCapabilityPreviewResolver previewResolver;
    private ActiveRunCapabilityBindingQuery activeRunBindingQuery;
    private WorkspaceDevelopmentContextGateway developmentContextGateway;
    private WorkspaceDevelopmentContext developmentContext;
    private PhaseCapabilityQueryService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        configurationRepository =
                mock(PhaseCapabilityConfigurationRepository.class);
        profileCatalog = mock(PhaseCapabilityProfileCatalog.class);
        previewResolver = mock(PhaseCapabilityPreviewResolver.class);
        activeRunBindingQuery = mock(ActiveRunCapabilityBindingQuery.class);
        developmentContextGateway =
                mock(WorkspaceDevelopmentContextGateway.class);
        developmentContext = developmentContext(
                RepositoryDevelopmentMarker.POM_XML);
        when(developmentContextGateway.inspect(any(RepositoryScope.class)))
                .thenReturn(developmentContext);
        service = new PhaseCapabilityQueryService(
                workbenchRepository, configurationRepository, profileCatalog,
                previewResolver, activeRunBindingQuery,
                developmentContextGateway);
    }

    @Test
    void effectiveProfileShouldComposeSafeOwnerScopedPreviewAndActiveBinding() {
        Workbench workbench = workbench();
        PhaseCapabilityProfile profile = profile();
        CapabilityOverride override = profile.overrideWithSelectedOptionals(
                Collections.singleton("java-tdd"),
                Collections.<String>emptySet(),
                AdditionalCapabilityRule.create("先跑聚焦测试", 100));
        PhaseCapabilityConfiguration configuration =
                PhaseCapabilityConfiguration.restore(
                        WORKBENCH_ID, PHASE, profile.getProfileId(),
                        profile.getProfileVersion(), override,
                        OWNER, NOW, 3L, profile.getOverridePolicy());
        PhaseCapabilityOverrideResolution overrideResolution =
                configuration.resolveFor(WORKBENCH_ID, profile);
        PhaseCapabilityPreview preview = preview(
                profile, overrideResolution, CapabilityAccess.READ);
        String activeBindingHash = CanonicalHashing.sha256("active-binding");
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(configurationRepository.findState(WORKBENCH_ID, PHASE))
                .thenReturn(PhaseCapabilityConfigurationState.present(
                        configuration));
        when(profileCatalog.requireProfile(PHASE)).thenReturn(profile);
        when(previewResolver.resolve(
                eq(profile), any(PhaseCapabilityOverrideResolution.class),
                eq(AgentType.CODEX), eq(developmentContext)))
                .thenReturn(preview);
        when(activeRunBindingQuery.findActiveBindingHash(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.of(activeBindingHash));

        EffectivePhaseCapabilityView view = service.getEffectiveProfile(
                OWNER, WORKBENCH_ID, PHASE);

        assertEquals(PHASE, view.getPhase());
        assertEquals("AVAILABLE", view.getStatus());
        assertEquals(profile.getProfileId(), view.getProfileId());
        assertEquals(profile.getProfileHash(), view.getProfileHash());
        assertEquals(Collections.singletonList("java-tdd"),
                view.getOptionalSkillIds());
        assertTrue(view.getOptionalMcpServerIds().isEmpty());
        assertEquals("先跑聚焦测试", view.getAdditionalRule());
        assertEquals(3L, view.getOverrideVersion());
        assertEquals(activeBindingHash, view.getActiveRunSnapshotHash());
        assertEquals("NEXT_RUN", view.getEffectiveFrom());
        assertTrue(view.getRules().get(0).isRequired());
        assertFalse(view.getMcpServers().get(0).isSelected());
    }

    @Test
    void effectiveProfileWithoutOverrideShouldUseDefaultDomainOverrideVersionZero() {
        Workbench workbench = workbench();
        PhaseCapabilityProfile profile = profile();
        PhaseCapabilityOverrideResolution defaults =
                profile.defaultOverrideResolution();
        PhaseCapabilityPreview preview = preview(
                profile, defaults, CapabilityAccess.READ);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(configurationRepository.findState(WORKBENCH_ID, PHASE))
                .thenReturn(PhaseCapabilityConfigurationState.initiallyAbsent(
                        WORKBENCH_ID, PHASE));
        when(profileCatalog.requireProfile(PHASE)).thenReturn(profile);
        when(previewResolver.resolve(
                eq(profile), any(PhaseCapabilityOverrideResolution.class),
                eq(AgentType.CODEX), eq(developmentContext)))
                .thenReturn(preview);
        when(activeRunBindingQuery.findActiveBindingHash(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.empty());

        EffectivePhaseCapabilityView view = service.getEffectiveProfile(
                OWNER, WORKBENCH_ID, PHASE);

        assertEquals(0L, view.getOverrideVersion());
        assertEquals("", view.getAdditionalRule());
        assertEquals(null, view.getActiveRunSnapshotHash());
        ArgumentCaptor<PhaseCapabilityOverrideResolution> captured =
                ArgumentCaptor.forClass(
                        PhaseCapabilityOverrideResolution.class);
        verify(previewResolver).resolve(
                eq(profile), captured.capture(), eq(AgentType.CODEX),
                eq(developmentContext));
        assertFalse(captured.getValue().getEffectiveOverride()
                .hasExplicitOptionalMcpSelection());
    }

    @Test
    void effectiveProfileAfterDeleteShouldExposeTombstoneConcurrencyToken() {
        PhaseCapabilityProfile profile = profile();
        PhaseCapabilityOverrideResolution defaults =
                profile.defaultOverrideResolution();
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.findState(WORKBENCH_ID, PHASE))
                .thenReturn(PhaseCapabilityConfigurationState.absent(
                        WORKBENCH_ID, PHASE, 6L));
        when(profileCatalog.requireProfile(PHASE)).thenReturn(profile);
        when(previewResolver.resolve(
                eq(profile), any(PhaseCapabilityOverrideResolution.class),
                eq(AgentType.CODEX), eq(developmentContext)))
                .thenReturn(preview(
                        profile, defaults, CapabilityAccess.READ));
        when(activeRunBindingQuery.findActiveBindingHash(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.empty());

        EffectivePhaseCapabilityView view = service.getEffectiveProfile(
                OWNER, WORKBENCH_ID, PHASE);

        assertEquals(6L, view.getOverrideVersion());
        assertEquals(Collections.singletonList("java-tdd"),
                view.getOptionalSkillIds());
    }

    @Test
    void overrideQueryShouldReturnPublicSelectionOrEmptyWithoutInternalFields() {
        PhaseCapabilityProfile profile = profile();
        CapabilityOverride override = profile.overrideWithSelectedOptionals(
                Collections.singleton("java-tdd"),
                Collections.<String>emptySet(), null);
        PhaseCapabilityConfiguration configuration =
                PhaseCapabilityConfiguration.restore(
                        WORKBENCH_ID, PHASE, profile.getProfileId(),
                        profile.getProfileVersion(), override,
                        OWNER, NOW, 4L, profile.getOverridePolicy());
        PhaseCapabilityOverrideResolution overrideResolution =
                configuration.resolveFor(WORKBENCH_ID, profile);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.of(configuration))
                .thenReturn(Optional.empty());
        when(profileCatalog.requireProfile(PHASE)).thenReturn(profile);
        when(previewResolver.resolve(
                eq(profile), any(PhaseCapabilityOverrideResolution.class),
                eq(AgentType.CODEX), eq(developmentContext)))
                .thenReturn(preview(
                        profile, overrideResolution, CapabilityAccess.READ));

        Optional<PublicPhaseCapabilityOverrideView> found =
                service.getOverride(OWNER, WORKBENCH_ID, PHASE);
        Optional<PublicPhaseCapabilityOverrideView> missing =
                service.getOverride(OWNER, WORKBENCH_ID, PHASE);

        assertTrue(found.isPresent());
        assertEquals(Collections.singletonList("java-tdd"),
                found.get().getOptionalSkillIds());
        assertTrue(found.get().getOptionalMcpServerIds().isEmpty());
        assertEquals("", found.get().getAdditionalRule());
        assertEquals(4L, found.get().getVersion());
        assertEquals(NOW.toEpochMilli(), found.get().getUpdatedAt());
        assertFalse(missing.isPresent());
        verify(profileCatalog).requireProfile(PHASE);
    }

    @Test
    void staleOverrideShouldRestoreDefaultAndExposeWarningInsteadOfFailing() {
        PhaseCapabilityProfile profile = profile();
        CapabilityOverride staleOverride =
                CapabilityOverride.withExplicitOptionalMcpSelection(
                        Collections.<String>emptySet(),
                        Collections.singleton("retired-skill"),
                        Collections.singleton("retired-query"),
                        Collections.<String>emptySet(), null);
        com.example.agentweb.domain.workbench.PhaseCapabilityOverridePolicy
                oldPolicy = com.example.agentweb.domain.workbench
                .PhaseCapabilityOverridePolicy.constrainedTo(
                        PHASE, Collections.singleton("retired-skill"),
                        Collections.singleton("retired-query"),
                        Collections.<String>emptySet(),
                        Collections.<String>emptySet());
        PhaseCapabilityConfiguration configuration =
                PhaseCapabilityConfiguration.restore(
                        WORKBENCH_ID, PHASE, profile.getProfileId(), "0.9.0",
                        staleOverride, OWNER, NOW, 9L, oldPolicy);
        PhaseCapabilityOverrideResolution degraded =
                configuration.resolveFor(WORKBENCH_ID, profile);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.findState(WORKBENCH_ID, PHASE))
                .thenReturn(PhaseCapabilityConfigurationState.present(
                        configuration));
        when(configurationRepository.find(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.of(configuration));
        when(profileCatalog.requireProfile(PHASE)).thenReturn(profile);
        when(previewResolver.resolve(
                eq(profile), any(PhaseCapabilityOverrideResolution.class),
                eq(AgentType.CODEX), eq(developmentContext)))
                .thenReturn(preview(
                        profile, degraded, CapabilityAccess.READ));

        EffectivePhaseCapabilityView view = service.getEffectiveProfile(
                OWNER, WORKBENCH_ID, PHASE);
        Optional<PublicPhaseCapabilityOverrideView> publicOverride =
                service.getOverride(OWNER, WORKBENCH_ID, PHASE);

        assertEquals("DEGRADED", view.getStatus());
        assertEquals(Collections.singletonList(
                        PhaseCapabilityOverrideResolution
                                .RESTORED_DEFAULT_WARNING),
                view.getWarnings());
        assertEquals(Collections.singletonList("java-tdd"),
                view.getOptionalSkillIds());
        assertEquals(Collections.singletonList("repository-query"),
                view.getOptionalMcpServerIds());
        assertEquals(9L, view.getOverrideVersion());
        assertTrue(publicOverride.isPresent());
        assertEquals(Collections.singletonList("java-tdd"),
                publicOverride.get().getOptionalSkillIds());
        assertEquals(Collections.singletonList("repository-query"),
                publicOverride.get().getOptionalMcpServerIds());
        assertEquals("", publicOverride.get().getAdditionalRule());
        assertEquals(9L, publicOverride.get().getVersion());
        ArgumentCaptor<PhaseCapabilityOverrideResolution> captured =
                ArgumentCaptor.forClass(
                        PhaseCapabilityOverrideResolution.class);
        verify(previewResolver, org.mockito.Mockito.times(2)).resolve(
                eq(profile), captured.capture(), eq(AgentType.CODEX),
                eq(developmentContext));
        assertTrue(captured.getAllValues().get(0).getEffectiveOverride()
                .getRemovedOptionalSkillIds().isEmpty());
        assertFalse(captured.getAllValues().get(0).getEffectiveOverride()
                .hasExplicitOptionalMcpSelection());
    }

    @Test
    void effectiveProfileShouldProjectTrustedWriteAccessOnlyForMcpItems() {
        PhaseCapabilityProfile profile = profile();
        CapabilityOverride override = profile.overrideWithSelectedOptionals(
                Collections.singleton("java-tdd"),
                Collections.singleton("repository-query"), null);
        PhaseCapabilityConfiguration configuration =
                PhaseCapabilityConfiguration.restore(
                        WORKBENCH_ID, PHASE, profile.getProfileId(),
                        profile.getProfileVersion(), override,
                        OWNER, NOW, 5L, profile.getOverridePolicy());
        PhaseCapabilityOverrideResolution resolution =
                configuration.resolveFor(WORKBENCH_ID, profile);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.findState(WORKBENCH_ID, PHASE))
                .thenReturn(PhaseCapabilityConfigurationState.present(
                        configuration));
        when(profileCatalog.requireProfile(PHASE)).thenReturn(profile);
        when(previewResolver.resolve(
                eq(profile), any(PhaseCapabilityOverrideResolution.class),
                eq(AgentType.CODEX), eq(developmentContext)))
                .thenReturn(preview(
                        profile, resolution, CapabilityAccess.WRITE));

        EffectivePhaseCapabilityView view = service.getEffectiveProfile(
                OWNER, WORKBENCH_ID, PHASE);

        assertNull(view.getRules().get(0).getAccess());
        assertNull(view.getSkills().get(0).getAccess());
        assertEquals("WRITE", view.getMcpServers().get(0).getAccess());
    }

    @Test
    void effectiveProfileShouldMatchRunTechnologyFiltering() {
        assertDrawerMatchesRun(
                RepositoryDevelopmentMarker.POM_XML,
                Collections.singletonList("java-specialist"),
                Collections.singletonList(
                        "python-specialist:OPTIONAL_SKILL_TECHNOLOGY_MISMATCH"));
    }

    @Test
    void effectiveProfileShouldMatchRunPythonTechnologyFiltering() {
        assertDrawerMatchesRun(
                RepositoryDevelopmentMarker.PYPROJECT_TOML,
                Collections.singletonList("python-specialist"),
                Collections.singletonList(
                        "java-specialist:OPTIONAL_SKILL_TECHNOLOGY_MISMATCH"));
    }

    @Test
    void effectiveProfileShouldKeepPlatformDefaultsWithoutTechnologyMarker() {
        assertDrawerMatchesRun(
                RepositoryDevelopmentMarker.README_MARKDOWN,
                Arrays.asList("java-specialist", "python-specialist"),
                Collections.<String>emptyList());
    }

    private void assertDrawerMatchesRun(
            RepositoryDevelopmentMarker marker,
            List<String> expectedSkillIds,
            List<String> expectedWarnings) {
        PhaseCapabilityProfile profile = technologyProfile();
        PhaseCapabilityBindingResolver bindingResolver =
                technologyBindingResolver();
        PhaseCapabilityQueryService technologyAwareQuery =
                new PhaseCapabilityQueryService(
                        workbenchRepository, configurationRepository,
                        profileCatalog,
                        new PhaseCapabilityPreviewResolver(
                                bindingResolver,
                                "workbench-policy@1"),
                        activeRunBindingQuery, developmentContextGateway);
        WorkspaceDevelopmentContext repositoryContext =
                developmentContext(marker);
        when(developmentContextGateway.inspect(any(RepositoryScope.class)))
                .thenReturn(repositoryContext);
        Workbench ownedWorkbench = workbench();
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(ownedWorkbench));
        when(configurationRepository.findState(WORKBENCH_ID, PHASE))
                .thenReturn(PhaseCapabilityConfigurationState.initiallyAbsent(
                        WORKBENCH_ID, PHASE));
        when(profileCatalog.requireProfile(PHASE)).thenReturn(profile);
        when(activeRunBindingQuery.findActiveBindingHash(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.empty());

        EffectivePhaseCapabilityView drawer =
                technologyAwareQuery.getEffectiveProfile(
                        OWNER, WORKBENCH_ID, PHASE);
        ResolvedCapabilityBinding runBinding =
                bindingResolver.resolve(
                        profile, CapabilityOverride.empty(),
                        PhaseCapabilityResolutionPolicy.forRun(
                                "workbench-policy@1",
                                RunMode.MODIFY_WORKSPACE,
                                AgentType.CODEX.name(),
                                "CODEX_WORKBENCH@1",
                                Collections.singleton(
                                        SkillTrustSource.PLATFORM),
                                repositoryContext));

        assertEquals(skillIds(runBinding), availableSkillIds(drawer));
        assertEquals(expectedSkillIds, availableSkillIds(drawer));
        assertEquals(expectedWarnings, drawer.getWarnings());
        verify(developmentContextGateway).inspect(
                ownedWorkbench.getRepositoryScope());
    }

    @Test
    void archivedOwnerMayReadButForeignOwnerIsObscuredBeforeCapabilityLookup() {
        Workbench archived = workbench();
        archived.archive(OWNER, NOW.plusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(archived));
        when(configurationRepository.find(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.empty());

        assertFalse(service.getOverride(
                OWNER, WORKBENCH_ID, PHASE).isPresent());
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.getOverride(OTHER, WORKBENCH_ID, PHASE));

        verify(configurationRepository).find(WORKBENCH_ID, PHASE);
        verify(profileCatalog, never()).requireProfile(PHASE);
        verifyNoInteractions(
                previewResolver, activeRunBindingQuery,
                developmentContextGateway);
    }

    private static PhaseCapabilityPreview preview(
            PhaseCapabilityProfile profile,
            PhaseCapabilityOverrideResolution resolution,
            CapabilityAccess mcpAccess) {
        CapabilityOverride override = resolution.getEffectiveOverride();
        PhaseCapabilityReference mcpReference = new PhaseCapabilityReference(
                "repository-query", PhaseCapabilityType.MCP_SERVER, false);
        java.util.List<ResolvedMcpServerBinding> mcpServers =
                override.includes(mcpReference)
                        ? Collections.singletonList(
                        new ResolvedMcpServerBinding(
                                "repository-query", "1.0.0",
                                CanonicalHashing.sha256("mcp"),
                                mcpAccess, "STDIO"))
                        : Collections.<ResolvedMcpServerBinding>emptyList();
        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                "workbench-policy@1", profile.getProfileId(),
                profile.getProfileVersion(), profile.getProfileHash(),
                Collections.singletonList(new ResolvedRuleBinding(
                        "platform/workbench-safety", "1.0.0", "PLATFORM",
                        CanonicalHashing.sha256("rule"), true, "Safety")),
                Collections.singletonList(new ResolvedSkillBinding(
                        "java-tdd", "1.0.0", "PLATFORM",
                        CanonicalHashing.sha256("skill"), "PLATFORM")),
                mcpServers,
                Collections.emptyList(), "CODEX_WORKBENCH@1");
        return PhaseCapabilityPreview.create(profile, resolution, binding);
    }

    private static PhaseCapabilityProfile profile() {
        return PhaseCapabilityProfile.create(
                "implement-profile", "1.0.0", PHASE,
                Arrays.asList(
                        new PhaseCapabilityReference(
                                "platform/workbench-safety",
                                PhaseCapabilityType.RULE, true),
                        new PhaseCapabilityReference(
                                "java-tdd", PhaseCapabilityType.SKILL, false),
                        new PhaseCapabilityReference(
                                "repository-query",
                                PhaseCapabilityType.MCP_SERVER, false)));
    }

    private static Workbench workbench() {
        RepositorySelection selection = RepositorySelection.of(
                "repo", Collections.singletonList("repo"));
        ResolvedRepository repository = ResolvedRepository.fromVerifiedFacts(
                "repo", "/workspace/repo", repeat('a'), false);
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(repository), 50);
        WorkspaceSnapshotReference snapshot = new WorkspaceSnapshotReference(
                "snapshot-1",
                WorkspaceTopology.of("/workspace", selection).getTopologyHash(),
                repeat('b'), 1);
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "Goal",
                AgentType.CODEX, "test", scope, snapshot, NOW);
    }

    private static PhaseCapabilityProfile technologyProfile() {
        return PhaseCapabilityProfile.create(
                "technology-profile", "1.0.0", PHASE,
                Arrays.asList(
                        new PhaseCapabilityReference(
                                "java-specialist",
                                PhaseCapabilityType.SKILL, false),
                        new PhaseCapabilityReference(
                                "python-specialist",
                                PhaseCapabilityType.SKILL, false)));
    }

    private static PhaseCapabilityBindingResolver
            technologyBindingResolver() {
        RuleCatalog ruleCatalog = mock(RuleCatalog.class);
        SkillCatalog skillCatalog = mock(SkillCatalog.class);
        McpServerCatalog mcpServerCatalog = mock(McpServerCatalog.class);
        when(skillCatalog.discover()).thenReturn(Arrays.asList(
                skill("java-specialist", "java"),
                skill("python-specialist", "python")));
        when(mcpServerCatalog.discover()).thenReturn(Collections.emptyList());
        return new PhaseCapabilityBindingResolver(
                ruleCatalog, skillCatalog, mcpServerCatalog);
    }

    private static SkillPackage skill(String id, String technologyTag) {
        SkillManifest manifest = new SkillManifest(
                id, "1.0.0", "Skill " + id,
                Collections.singleton(PHASE.name()),
                Collections.singleton(technologyTag),
                Collections.<String>emptySet(), "SKILL.md",
                Collections.<String>emptySet(),
                Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet(),
                Collections.singleton(AgentType.CODEX.name()),
                SkillTrustSource.PLATFORM,
                Collections.<CapabilityRequest>emptyList());
        return new SkillPackage(
                manifest, CanonicalHashing.sha256(id),
                "# " + id, Collections.<String, String>emptyMap());
    }

    private static WorkspaceDevelopmentContext developmentContext(
            RepositoryDevelopmentMarker marker) {
        RepositoryScope scope = workbench().getRepositoryScope();
        return WorkspaceDevelopmentContext.create(
                scope.getScopeHash(), scope.getPrimaryRepositoryKey(),
                Collections.singletonList(
                        new RepositoryDevelopmentContextClassifier().classify(
                                scope.getPrimaryRepositoryKey(),
                                new LinkedHashSet<RepositoryDevelopmentMarker>(
                                        Collections.singleton(marker)))));
    }

    private static List<String> availableSkillIds(
            EffectivePhaseCapabilityView view) {
        List<String> result = new ArrayList<String>();
        for (CapabilityPreviewItemView skill : view.getSkills()) {
            if (!"UNAVAILABLE".equals(skill.getSource())) {
                result.add(skill.getId());
            }
        }
        return result;
    }

    private static List<String> skillIds(
            ResolvedCapabilityBinding binding) {
        List<String> result = new ArrayList<String>();
        for (ResolvedSkillBinding skill : binding.getSkills()) {
            result.add(skill.getId());
        }
        return result;
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }
}
