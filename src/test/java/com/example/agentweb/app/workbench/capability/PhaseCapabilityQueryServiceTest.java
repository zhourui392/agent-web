package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.capability.port.ActiveRunCapabilityBindingQuery;
import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.AdditionalCapabilityRule;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationRepository;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreview;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreviewResolver;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileCatalog;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private PhaseCapabilityQueryService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        configurationRepository =
                mock(PhaseCapabilityConfigurationRepository.class);
        profileCatalog = mock(PhaseCapabilityProfileCatalog.class);
        previewResolver = mock(PhaseCapabilityPreviewResolver.class);
        activeRunBindingQuery = mock(ActiveRunCapabilityBindingQuery.class);
        service = new PhaseCapabilityQueryService(
                workbenchRepository, configurationRepository, profileCatalog,
                previewResolver, activeRunBindingQuery);
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
        PhaseCapabilityPreview preview = preview(profile, override);
        String activeBindingHash = CanonicalHashing.sha256("active-binding");
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(configurationRepository.find(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.of(configuration));
        when(profileCatalog.requireProfile(PHASE)).thenReturn(profile);
        when(previewResolver.resolve(profile, override, AgentType.CODEX))
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
        CapabilityOverride defaults = CapabilityOverride.empty();
        PhaseCapabilityPreview preview = preview(profile, defaults);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(configurationRepository.find(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.empty());
        when(profileCatalog.requireProfile(PHASE)).thenReturn(profile);
        when(previewResolver.resolve(any(), any(), any())).thenReturn(preview);
        when(activeRunBindingQuery.findActiveBindingHash(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.empty());

        EffectivePhaseCapabilityView view = service.getEffectiveProfile(
                OWNER, WORKBENCH_ID, PHASE);

        assertEquals(0L, view.getOverrideVersion());
        assertEquals("", view.getAdditionalRule());
        assertEquals(null, view.getActiveRunSnapshotHash());
        ArgumentCaptor<CapabilityOverride> captured =
                ArgumentCaptor.forClass(CapabilityOverride.class);
        verify(previewResolver).resolve(
                eq(profile), captured.capture(), eq(AgentType.CODEX));
        assertFalse(captured.getValue().hasExplicitOptionalMcpSelection());
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
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(configurationRepository.find(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.of(configuration))
                .thenReturn(Optional.empty());
        when(profileCatalog.requireProfile(PHASE)).thenReturn(profile);
        when(previewResolver.resolve(profile, override, AgentType.CODEX))
                .thenReturn(preview(profile, override));

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
        verifyNoInteractions(previewResolver, activeRunBindingQuery);
    }

    private static PhaseCapabilityPreview preview(
            PhaseCapabilityProfile profile, CapabilityOverride override) {
        PhaseCapabilityReference mcpReference = new PhaseCapabilityReference(
                "repository-query", PhaseCapabilityType.MCP_SERVER, false);
        java.util.List<ResolvedMcpServerBinding> mcpServers =
                override.includes(mcpReference)
                        ? Collections.singletonList(
                        new ResolvedMcpServerBinding(
                                "repository-query", "1.0.0",
                                CanonicalHashing.sha256("mcp"),
                                CapabilityAccess.READ, "STDIO"))
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
        return PhaseCapabilityPreview.create(profile, override, binding);
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

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }
}
