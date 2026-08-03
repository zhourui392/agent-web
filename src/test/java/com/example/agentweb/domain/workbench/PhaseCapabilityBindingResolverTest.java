package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.CapabilityRequest;
import com.example.agentweb.domain.capability.CapabilityResolutionException;
import com.example.agentweb.domain.capability.McpCapability;
import com.example.agentweb.domain.capability.McpCapabilityType;
import com.example.agentweb.domain.capability.McpSecretReference;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.capability.RuleCatalog;
import com.example.agentweb.domain.capability.RuleDefinition;
import com.example.agentweb.domain.capability.RuleResource;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.capability.SkillDependency;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 默认请求、当前 Override 与可信 Catalog 的最终能力绑定解析测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseCapabilityBindingResolverTest {

    private static final String POLICY_VERSION = "workbench-policy@1";
    private static final String CODEX = "CODEX";
    private static final String CODEX_COMPATIBILITY = "CODEX_SUPPORTED@1";
    private static final WorkbenchPhase PHASE = WorkbenchPhase.REQUIREMENT_ANALYSIS;
    private static final String USE_CASE = "REQUIREMENT_ANALYSIS";

    @Test
    void shouldResolveAllProfileReferencesAsDefaultRequests() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("platform/safety", PhaseCapabilityType.RULE),
                optional("analysis/style", PhaseCapabilityType.RULE),
                optional("code-search", PhaseCapabilityType.SKILL),
                optional("repository-query", PhaseCapabilityType.MCP_SERVER));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(
                        rule("platform/safety", true),
                        rule("analysis/style", false)),
                skills(skill("code-search")),
                mcpServers(mcp("repository-query", CapabilityAccess.READ)));

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());

        // Then
        assertEquals(POLICY_VERSION, binding.getPolicyVersion());
        assertEquals(profile.getProfileId(), binding.getProfileId());
        assertEquals(profile.getProfileVersion(), binding.getProfileVersion());
        assertEquals(profile.getProfileHash(), binding.getProfileHash());
        assertEquals(CODEX_COMPATIBILITY, binding.getRuntimeCompatibility());
        assertEquals(Arrays.asList("analysis/style", "platform/safety"), ruleIds(binding));
        assertEquals(Collections.singletonList("code-search"), skillIds(binding));
        assertEquals(Collections.singletonList("repository-query"), mcpIds(binding));
        assertTrue(ruleBinding(binding, "platform/safety").isMandatory());
        assertFalse(ruleBinding(binding, "analysis/style").isMandatory());
        assertEquals("PLATFORM", binding.getSkills().get(0).getTrustTier());
        assertEquals(CapabilityAccess.READ, binding.getMcpServers().get(0).getAccess());
        assertEquals("STDIO", binding.getMcpServers().get(0).getTransport());
        assertTrue(binding.getRejected().isEmpty());
        assertTrue(binding.getBindingHash().matches("[a-f0-9]{64}"));
    }

    @Test
    void shouldUseRepositoryTechnologyFactsToNarrowOptionalSkills() {
        PhaseCapabilityProfile profile = profile(
                optional("java-specialist", PhaseCapabilityType.SKILL),
                optional("general-analysis", PhaseCapabilityType.SKILL));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(
                        skillWithTags("java-specialist", strings("java")),
                        skillWithTags(
                                "general-analysis",
                                Collections.<String>emptySet())),
                mcpServers());
        WorkspaceDevelopmentContext pythonContext =
                WorkspaceDevelopmentContext.create(
                        repeat('a'), "agent-web",
                        Collections.singletonList(
                                new RepositoryDevelopmentContextClassifier()
                                        .classify("agent-web", stringsOfMarkers(
                                                RepositoryDevelopmentMarker
                                                        .PYPROJECT_TOML))));

        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(),
                PhaseCapabilityResolutionPolicy.forRun(
                        POLICY_VERSION, RunMode.DISCUSS_READ_ONLY,
                        CODEX, CODEX_COMPATIBILITY,
                        Collections.singleton(SkillTrustSource.PLATFORM),
                        pythonContext));

        assertEquals(Collections.singletonList("general-analysis"),
                skillIds(binding));
        assertEquals("OPTIONAL_SKILL_TECHNOLOGY_MISMATCH",
                rejectionReason(binding, "java-specialist"));
    }

    @Test
    void shouldKeepPlatformDefaultSkillWhenRepositoryHasNoTechnologyMarker() {
        PhaseCapabilityProfile profile = profile(
                optional("java-specialist", PhaseCapabilityType.SKILL));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(skillWithTags(
                        "java-specialist", strings("java"))),
                mcpServers());
        WorkspaceDevelopmentContext unknownContext =
                WorkspaceDevelopmentContext.create(
                        repeat('a'), "agent-web",
                        Collections.singletonList(
                                new RepositoryDevelopmentContextClassifier()
                                        .classify("agent-web", stringsOfMarkers(
                                                RepositoryDevelopmentMarker
                                                        .README_MARKDOWN))));

        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(),
                PhaseCapabilityResolutionPolicy.forRun(
                        POLICY_VERSION, RunMode.DISCUSS_READ_ONLY,
                        CODEX, CODEX_COMPATIBILITY,
                        Collections.singleton(SkillTrustSource.PLATFORM),
                        unknownContext));

        assertEquals(Collections.singletonList("java-specialist"),
                skillIds(binding));
        assertTrue(binding.getRejected().isEmpty());
    }

    @Test
    void shouldHonorExplicitEmptyOptionalMcpSelectionWithoutRemovingRequiredMcp() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("required-repository-query", PhaseCapabilityType.MCP_SERVER),
                optional("optional-issue-query", PhaseCapabilityType.MCP_SERVER));
        CapabilityOverride currentOverride =
                CapabilityOverride.withExplicitOptionalMcpSelection(
                        Collections.<String>emptySet(),
                        Collections.<String>emptySet(),
                        Collections.<String>emptySet(),
                        Collections.<String>emptySet(),
                        null);
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(), mcpServers(
                        mcp("required-repository-query", CapabilityAccess.READ),
                        mcp("optional-issue-query", CapabilityAccess.READ)));

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, currentOverride, readOnlyPolicy());

        // Then
        assertEquals(Collections.singletonList("required-repository-query"),
                mcpIds(binding));
        assertTrue(binding.getRejected().isEmpty());
    }

    @Test
    void shouldNarrowDefaultRequestsWithCurrentOverride() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("platform/safety", PhaseCapabilityType.RULE),
                optional("analysis/concise", PhaseCapabilityType.RULE),
                optional("analysis/detailed", PhaseCapabilityType.RULE),
                optional("code-search", PhaseCapabilityType.SKILL),
                optional("service-navigation", PhaseCapabilityType.SKILL),
                optional("repository-query", PhaseCapabilityType.MCP_SERVER),
                optional("issue-query", PhaseCapabilityType.MCP_SERVER));
        CapabilityOverride currentOverride = CapabilityOverride.of(
                Collections.singleton("code-search"),
                Collections.singleton("service-navigation"),
                Collections.singleton("repository-query"),
                Collections.singleton("analysis/concise"));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(
                        rule("platform/safety", true),
                        rule("analysis/concise", false),
                        rule("analysis/detailed", false)),
                skills(skill("code-search"), skill("service-navigation")),
                mcpServers(
                        mcp("repository-query", CapabilityAccess.READ),
                        mcp("issue-query", CapabilityAccess.READ)));

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, currentOverride, readOnlyPolicy());

        // Then
        assertEquals(Arrays.asList("analysis/concise", "platform/safety"), ruleIds(binding));
        assertEquals(Collections.singletonList("code-search"), skillIds(binding));
        assertEquals(Collections.singletonList("repository-query"), mcpIds(binding));
        assertTrue(binding.getRejected().isEmpty());
    }

    @Test
    void shouldFreezeAdditionalOwnerRuleHashIntoResolvedBinding() {
        PhaseCapabilityProfile profile = profile(
                required("platform/safety", PhaseCapabilityType.RULE));
        AdditionalCapabilityRule additionalRule =
                AdditionalCapabilityRule.create(
                        "输出前再次核对影响范围", 4000);
        CapabilityOverride currentOverride = CapabilityOverride.of(
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(), additionalRule);
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(rule("platform/safety", true)),
                skills(), mcpServers());

        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, currentOverride, readOnlyPolicy());

        assertEquals(2, binding.getRules().size());
        ResolvedRuleBinding ownerRule = binding.getRules().stream()
                .filter(rule -> "WORKBENCH_OWNER_OVERRIDE".equals(
                        rule.getSource()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals(CanonicalHashing.sha256(additionalRule.getValue()),
                ownerRule.getContentHash());
        assertFalse(ownerRule.isMandatory());
        assertFalse(ownerRule.getSafeSummary()
                .contains(additionalRule.getValue()));

        ResolvedCapabilityBinding withoutAdditional = resolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());
        assertNotEquals(withoutAdditional.getBindingHash(),
                binding.getBindingHash());
    }

    @Test
    void shouldResolveExactCatalogAndOwnerContentsForPrivateRunPrompt() {
        String catalogContent =
                "Rule content for platform/safety@1.0.0";
        String ownerContent = "输出前再次核对影响范围";
        PhaseCapabilityProfile profile = profile(
                required("platform/safety", PhaseCapabilityType.RULE));
        CapabilityOverride currentOverride = CapabilityOverride.of(
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                AdditionalCapabilityRule.create(ownerContent, 4000));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(rule("platform/safety", true)),
                skills(), mcpServers());

        ResolvedCapabilityResolution resolution = resolver.resolveForRun(
                profile, currentOverride, readOnlyPolicy());

        assertEquals(2, resolution.getRuleContents().size());
        assertEquals(2, resolution.getBinding().getRules().size());
        for (ResolvedCapabilityRuleContent content
                : resolution.getRuleContents()) {
            ResolvedRuleBinding binding = ruleBinding(
                    resolution.getBinding(), content.getId());
            content.requireExactBinding(binding);
            assertEquals(CanonicalHashing.sha256(content.getContent()),
                    binding.getContentHash());
        }
        assertEquals(catalogContent,
                resolution.getRuleContents().get(0).getContent());
        assertEquals(ownerContent,
                resolution.getRuleContents().get(1).getContent());
        assertFalse(resolution.getBinding().getRules().get(0)
                .getSafeSummary().contains(catalogContent));
        assertFalse(resolution.getBinding().getRules().get(1)
                .getSafeSummary().contains(ownerContent));
    }

    @Test
    void shouldAlwaysBindCatalogMandatoryRuleEvenWhenOptionalProfileOverrideOmitsIt() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("platform/mandatory-safety", PhaseCapabilityType.RULE),
                optional("analysis/selected-style", PhaseCapabilityType.RULE));
        CapabilityOverride currentOverride = CapabilityOverride.of(
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(),
                Collections.singleton("analysis/selected-style"));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(
                        rule("platform/mandatory-safety", true),
                        rule("analysis/selected-style", false)),
                skills(), mcpServers());

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, currentOverride, readOnlyPolicy());

        // Then
        assertEquals(
                Arrays.asList("analysis/selected-style", "platform/mandatory-safety"),
                ruleIds(binding));
        assertTrue(ruleBinding(binding, "platform/mandatory-safety").isMandatory());
        assertTrue(binding.getRejected().isEmpty());
    }

    @Test
    void shouldFailWhenRequiredRuleIsMissing() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("platform/safety", PhaseCapabilityType.RULE));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(), mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldFailWhenRequiredSkillIsMissing() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("required-analysis", PhaseCapabilityType.SKILL));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(), mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldFailWhenRequiredMcpServerIsMissing() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("required-query", PhaseCapabilityType.MCP_SERVER));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(), mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldFailWhenRequiredRuleDoesNotSupportCurrentPhaseUseCase() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("design-only-rule", PhaseCapabilityType.RULE));
        RuleDefinition designOnly = rule(
                "design-only-rule", "1.0.0", true,
                strings("SOLUTION_DESIGN"));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(designOnly), skills(), mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldFailWhenRequiredSkillIsRuntimeIncompatible() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("required-claude-skill", PhaseCapabilityType.SKILL));
        SkillPackage claudeOnly = skill(
                "required-claude-skill", "1.0.0", SkillTrustSource.PLATFORM,
                strings("CLAUDE"), Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(claudeOnly), mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldFailWhenRequiredSkillComesFromUntrustedSource() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("required-workspace-skill", PhaseCapabilityType.SKILL));
        SkillPackage workspaceSkill = skill(
                "required-workspace-skill", "1.0.0", SkillTrustSource.WORKSPACE,
                strings(CODEX), Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(workspaceSkill), mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldFailWhenRequiredSkillDependencyIsUnavailable() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("required-dependent-skill", PhaseCapabilityType.SKILL));
        SkillPackage dependent = skill(
                "required-dependent-skill", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX),
                Collections.singletonList(new SkillDependency("missing-base", "1.0.0")),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(dependent), mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldResolveExactCatalogDependencyNotDeclaredByProfile() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("dependent-analysis", PhaseCapabilityType.SKILL));
        SkillPackage dependent = skill(
                "dependent-analysis", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.singletonList(
                        new SkillDependency("analysis-base", "2.0.0")),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(dependent, skill("analysis-base", "2.0.0")),
                mcpServers());

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());

        // Then
        assertEquals(Arrays.asList("analysis-base", "dependent-analysis"),
                skillIds(binding));
        assertEquals("2.0.0", binding.getSkills().get(0).getVersion());
        assertTrue(binding.getRejected().isEmpty());
    }

    @Test
    void shouldBlockRequiredRootForUntrustedOrRuntimeIncompatibleDependency() {
        // Given
        SkillPackage untrustedRoot = skill(
                "untrusted-dependent", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.singletonList(
                        new SkillDependency("workspace-base", "1.0.0")),
                Collections.<String>emptySet());
        SkillPackage workspaceBase = skill(
                "workspace-base", "1.0.0", SkillTrustSource.WORKSPACE,
                strings(CODEX), Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet());
        SkillPackage runtimeRoot = skill(
                "runtime-dependent", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.singletonList(
                        new SkillDependency("claude-base", "1.0.0")),
                Collections.<String>emptySet());
        SkillPackage claudeBase = skill(
                "claude-base", "1.0.0", SkillTrustSource.PLATFORM,
                strings("CLAUDE"), Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet());

        // When
        CapabilityResolutionException untrustedFailure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver(
                        rules(), skills(untrustedRoot, workspaceBase),
                        mcpServers()).resolve(
                        profile(required(
                                "untrusted-dependent",
                                PhaseCapabilityType.SKILL)),
                        CapabilityOverride.empty(), readOnlyPolicy()));
        CapabilityResolutionException runtimeFailure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver(
                        rules(), skills(runtimeRoot, claudeBase),
                        mcpServers()).resolve(
                        profile(required(
                                "runtime-dependent",
                                PhaseCapabilityType.SKILL)),
                        CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(untrustedFailure);
        assertRequiredUnavailable(runtimeFailure);
    }

    @Test
    void shouldStablyRejectOptionalRootsWithUnusableAutomaticDependencies() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("untrusted-dependent", PhaseCapabilityType.SKILL),
                optional("runtime-dependent", PhaseCapabilityType.SKILL));
        SkillPackage untrustedRoot = skill(
                "untrusted-dependent", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.singletonList(
                        new SkillDependency("workspace-base", "1.0.0")),
                Collections.<String>emptySet());
        SkillPackage workspaceBase = skill(
                "workspace-base", "1.0.0", SkillTrustSource.WORKSPACE,
                strings(CODEX), Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet());
        SkillPackage runtimeRoot = skill(
                "runtime-dependent", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.singletonList(
                        new SkillDependency("claude-base", "1.0.0")),
                Collections.<String>emptySet());
        SkillPackage claudeBase = skill(
                "claude-base", "1.0.0", SkillTrustSource.PLATFORM,
                strings("CLAUDE"), Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(
                        untrustedRoot, workspaceBase, runtimeRoot, claudeBase),
                mcpServers());

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());

        // Then
        assertTrue(binding.getSkills().isEmpty());
        assertEquals("SKILL_DEPENDENCY_UNAVAILABLE",
                rejectionReason(binding, "untrusted-dependent"));
        assertEquals("SKILL_DEPENDENCY_UNAVAILABLE",
                rejectionReason(binding, "runtime-dependent"));
    }

    @Test
    void shouldFailClosedWithStableCodeForDependencyCycle() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("cycle-alpha", PhaseCapabilityType.SKILL));
        SkillPackage alpha = skill(
                "cycle-alpha", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.singletonList(
                        new SkillDependency("cycle-beta", "1.0.0")),
                Collections.<String>emptySet());
        SkillPackage beta = skill(
                "cycle-beta", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.singletonList(
                        new SkillDependency("cycle-alpha", "1.0.0")),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(alpha, beta), mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertEquals("SKILL_DEPENDENCY_CYCLE", failure.getCode());
    }

    @Test
    void shouldNotReAddRemovedOptionalDependencyForRequiredRoot() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("required-dependent", PhaseCapabilityType.SKILL),
                optional("removable-base", PhaseCapabilityType.SKILL));
        SkillPackage dependent = skill(
                "required-dependent", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.singletonList(
                        new SkillDependency("removable-base", "1.0.0")),
                Collections.<String>emptySet());
        CapabilityOverride currentOverride = CapabilityOverride.of(
                Collections.<String>emptySet(),
                Collections.singleton("removable-base"),
                Collections.<String>emptySet(),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(dependent, skill("removable-base")),
                mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, currentOverride, readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldRejectOptionalRootWithoutReAddingRemovedDependency() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("optional-dependent", PhaseCapabilityType.SKILL),
                optional("removable-base", PhaseCapabilityType.SKILL));
        SkillPackage dependent = skill(
                "optional-dependent", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.singletonList(
                        new SkillDependency("removable-base", "1.0.0")),
                Collections.<String>emptySet());
        CapabilityOverride currentOverride = CapabilityOverride.of(
                Collections.<String>emptySet(),
                Collections.singleton("removable-base"),
                Collections.<String>emptySet(),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(dependent, skill("removable-base")),
                mcpServers());

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, currentOverride, readOnlyPolicy());

        // Then
        assertTrue(binding.getSkills().isEmpty());
        assertEquals(1, binding.getRejected().size());
        assertEquals("SKILL_DEPENDENCY_UNAVAILABLE",
                rejectionReason(binding, "optional-dependent"));
    }

    @Test
    void shouldApplyConflictRulesToAutomaticallyResolvedDependencies() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("dependent-analysis", PhaseCapabilityType.SKILL),
                optional("conflicting-analysis", PhaseCapabilityType.SKILL));
        SkillPackage dependent = skill(
                "dependent-analysis", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.singletonList(
                        new SkillDependency("analysis-base", "1.0.0")),
                Collections.<String>emptySet());
        SkillPackage base = skill(
                "analysis-base", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.<SkillDependency>emptyList(),
                Collections.singleton("conflicting-analysis"));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(
                        dependent, base, skill("conflicting-analysis")),
                mcpServers());

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());

        // Then
        assertTrue(binding.getSkills().isEmpty());
        assertEquals("SKILL_DEPENDENCY_UNAVAILABLE",
                rejectionReason(binding, "dependent-analysis"));
        assertEquals("SKILL_CONFLICT",
                rejectionReason(binding, "conflicting-analysis"));
    }

    @Test
    void shouldFailWhenRequiredSkillConflictsWithSelectedSkill() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("required-alpha-skill", PhaseCapabilityType.SKILL),
                optional("beta-skill", PhaseCapabilityType.SKILL));
        SkillPackage requiredAlpha = skill(
                "required-alpha-skill", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.<SkillDependency>emptyList(),
                Collections.singleton("beta-skill"));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(requiredAlpha, skill("beta-skill")), mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldFailWhenRequiredMcpAccessExceedsReadOnlyPolicy() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("required-workspace-writer", PhaseCapabilityType.MCP_SERVER));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(), mcpServers(
                        mcp("required-workspace-writer", CapabilityAccess.WRITE)));

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldFailWhenRequiredMcpIsRuntimeIncompatible() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("required-claude-query", PhaseCapabilityType.MCP_SERVER));
        McpServerDefinition claudeOnly = mcp(
                "required-claude-query", "1.0.0", CapabilityAccess.READ,
                strings("CLAUDE"));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(), mcpServers(claudeOnly));

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertRequiredUnavailable(failure);
    }

    @Test
    void shouldKeepStableRejectionsForMissingOptionalCapabilities() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("analysis/style", PhaseCapabilityType.RULE),
                optional("code-search", PhaseCapabilityType.SKILL),
                optional("repository-query", PhaseCapabilityType.MCP_SERVER));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(), mcpServers());

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());

        // Then
        assertTrue(binding.getRules().isEmpty());
        assertTrue(binding.getSkills().isEmpty());
        assertTrue(binding.getMcpServers().isEmpty());
        assertEquals("OPTIONAL_RULE_UNAVAILABLE",
                rejectionReason(binding, "analysis/style"));
        assertEquals("OPTIONAL_SKILL_UNAVAILABLE",
                rejectionReason(binding, "code-search"));
        assertEquals("OPTIONAL_MCP_UNAVAILABLE",
                rejectionReason(binding, "repository-query"));
    }

    @Test
    void shouldNeverElevateReadOnlyMcpAccessThroughOverride() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("workspace-writer", PhaseCapabilityType.MCP_SERVER));
        CapabilityOverride currentOverride = CapabilityOverride.of(
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.singleton("workspace-writer"),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(),
                mcpServers(mcp("workspace-writer", CapabilityAccess.WRITE)));

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, currentOverride, readOnlyPolicy());

        // Then
        assertTrue(binding.getMcpServers().isEmpty());
        assertEquals("MCP_ACCESS_DENIED",
                rejectionReason(binding, "workspace-writer"));
    }

    @Test
    void shouldRejectSkillOutsideTrustedCatalogSources() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("workspace-skill", PhaseCapabilityType.SKILL));
        SkillPackage workspaceSkill = skill(
                "workspace-skill", "1.0.0", SkillTrustSource.WORKSPACE,
                strings(CODEX), Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(workspaceSkill), mcpServers());

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());

        // Then
        assertTrue(binding.getSkills().isEmpty());
        assertEquals("SKILL_TRUST_DENIED",
                rejectionReason(binding, "workspace-skill"));
    }

    @Test
    void shouldRejectRuntimeIncompatibleSkill() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("claude-only", PhaseCapabilityType.SKILL));
        SkillPackage claudeOnly = skill(
                "claude-only", "1.0.0", SkillTrustSource.PLATFORM,
                strings("CLAUDE"), Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(claudeOnly), mcpServers());

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());

        // Then
        assertTrue(binding.getSkills().isEmpty());
        assertEquals("RUNTIME_INCOMPATIBLE",
                rejectionReason(binding, "claude-only"));
    }

    @Test
    void shouldRejectSkillWithMissingDependency() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("dependent-analysis", PhaseCapabilityType.SKILL));
        SkillPackage dependent = skill(
                "dependent-analysis", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX),
                Collections.singletonList(new SkillDependency("missing-base", "1.0.0")),
                Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(dependent), mcpServers());

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());

        // Then
        assertTrue(binding.getSkills().isEmpty());
        assertEquals("SKILL_DEPENDENCY_UNAVAILABLE",
                rejectionReason(binding, "dependent-analysis"));
    }

    @Test
    void shouldRejectBothSidesOfSkillConflictDeterministically() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("alpha-analysis", PhaseCapabilityType.SKILL),
                optional("beta-analysis", PhaseCapabilityType.SKILL));
        SkillPackage alpha = skill(
                "alpha-analysis", "1.0.0", SkillTrustSource.PLATFORM,
                strings(CODEX), Collections.<SkillDependency>emptyList(),
                Collections.singleton("beta-analysis"));
        SkillPackage beta = skill("beta-analysis");
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(beta, alpha), mcpServers());

        // When
        ResolvedCapabilityBinding binding = resolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());

        // Then
        assertTrue(binding.getSkills().isEmpty());
        assertEquals("SKILL_CONFLICT",
                rejectionReason(binding, "alpha-analysis"));
        assertEquals("SKILL_CONFLICT",
                rejectionReason(binding, "beta-analysis"));
    }

    @Test
    void shouldFailClosedForDuplicateRuleCatalogIdentifier() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("analysis/style", PhaseCapabilityType.RULE));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(
                        rule("analysis/style", "1.0.0", false),
                        rule("analysis/style", "2.0.0", false)),
                skills(), mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertEquals("CAPABILITY_ID_DUPLICATE", failure.getCode());
    }

    @Test
    void shouldFailClosedForDuplicateSkillCatalogIdentifier() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("code-search", PhaseCapabilityType.SKILL));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(),
                skills(
                        skill("code-search", "1.0.0"),
                        skill("code-search", "2.0.0")),
                mcpServers());

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertEquals("CAPABILITY_ID_DUPLICATE", failure.getCode());
    }

    @Test
    void shouldFailClosedForDuplicateMcpCatalogIdentifier() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("repository-query", PhaseCapabilityType.MCP_SERVER));
        PhaseCapabilityBindingResolver resolver = resolver(
                rules(), skills(),
                mcpServers(
                        mcp("repository-query", "1.0.0", CapabilityAccess.READ),
                        mcp("repository-query", "2.0.0", CapabilityAccess.READ)));

        // When
        CapabilityResolutionException failure = assertThrows(
                CapabilityResolutionException.class,
                () -> resolver.resolve(
                        profile, CapabilityOverride.empty(), readOnlyPolicy()));

        // Then
        assertEquals("CAPABILITY_ID_DUPLICATE", failure.getCode());
    }

    @Test
    void shouldProduceSameBindingHashForShuffledCatalogInputs() {
        // Given
        PhaseCapabilityProfile profile = profile(
                required("platform/safety", PhaseCapabilityType.RULE),
                optional("alpha-search", PhaseCapabilityType.SKILL),
                optional("beta-search", PhaseCapabilityType.SKILL),
                optional("alpha-query", PhaseCapabilityType.MCP_SERVER),
                optional("beta-query", PhaseCapabilityType.MCP_SERVER));
        RuleDefinition safety = rule("platform/safety", true);
        SkillPackage alphaSkill = skill("alpha-search");
        SkillPackage betaSkill = skill("beta-search");
        McpServerDefinition alphaMcp = mcp("alpha-query", CapabilityAccess.READ);
        McpServerDefinition betaMcp = mcp("beta-query", CapabilityAccess.READ);
        PhaseCapabilityBindingResolver firstResolver = resolver(
                rules(safety), skills(alphaSkill, betaSkill),
                mcpServers(alphaMcp, betaMcp));
        PhaseCapabilityBindingResolver secondResolver = resolver(
                rules(safety), skills(betaSkill, alphaSkill),
                mcpServers(betaMcp, alphaMcp));

        // When
        ResolvedCapabilityBinding first = firstResolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());
        ResolvedCapabilityBinding second = secondResolver.resolve(
                profile, CapabilityOverride.empty(), readOnlyPolicy());

        // Then
        assertEquals(first.getRules(), second.getRules());
        assertEquals(first.getSkills(), second.getSkills());
        assertEquals(first.getMcpServers(), second.getMcpServers());
        assertEquals(first.getRejected(), second.getRejected());
        assertEquals(first.getBindingHash(), second.getBindingHash());
    }

    @Test
    void shouldKeepResolvedBindingUnchangedAfterCatalogAndOverrideInputsChange() {
        // Given
        PhaseCapabilityProfile profile = profile(
                optional("analysis/style", PhaseCapabilityType.RULE),
                optional("code-search", PhaseCapabilityType.SKILL),
                optional("repository-query", PhaseCapabilityType.MCP_SERVER));
        MutableRuleCatalog ruleCatalog = rules(rule("analysis/style", false));
        MutableSkillCatalog skillCatalog = skills(skill("code-search", "1.0.0"));
        MutableMcpServerCatalog mcpCatalog = mcpServers(
                mcp("repository-query", "1.0.0", CapabilityAccess.READ));
        Set<String> removedSkillInput = new LinkedHashSet<String>();
        CapabilityOverride currentOverride = CapabilityOverride.of(
                Collections.<String>emptySet(), removedSkillInput,
                Collections.<String>emptySet(), Collections.<String>emptySet());
        PhaseCapabilityBindingResolver resolver = resolver(
                ruleCatalog, skillCatalog, mcpCatalog);

        // When
        ResolvedCapabilityBinding original = resolver.resolve(
                profile, currentOverride, readOnlyPolicy());
        String originalHash = original.getBindingHash();
        removedSkillInput.add("code-search");
        ruleCatalog.replace(rule("analysis/style", "2.0.0", false));
        skillCatalog.replace(skill("code-search", "2.0.0"));
        mcpCatalog.replace(mcp(
                "repository-query", "2.0.0", CapabilityAccess.READ));
        ResolvedCapabilityBinding changed = resolver.resolve(
                profile, currentOverride, readOnlyPolicy());

        // Then
        assertTrue(currentOverride.getRemovedOptionalSkillIds().isEmpty());
        assertEquals(originalHash, original.getBindingHash());
        assertEquals("1.0.0", original.getRules().get(0).getVersion());
        assertEquals("1.0.0", original.getSkills().get(0).getVersion());
        assertEquals("1.0.0", original.getMcpServers().get(0).getVersion());
        assertNotEquals(originalHash, changed.getBindingHash());
        assertThrows(UnsupportedOperationException.class,
                () -> original.getSkills().clear());
    }

    private static PhaseCapabilityBindingResolver resolver(
            MutableRuleCatalog ruleCatalog,
            MutableSkillCatalog skillCatalog,
            MutableMcpServerCatalog mcpCatalog) {
        return new PhaseCapabilityBindingResolver(
                ruleCatalog, skillCatalog, mcpCatalog);
    }

    private static PhaseCapabilityResolutionPolicy readOnlyPolicy() {
        return PhaseCapabilityResolutionPolicy.forRun(
                POLICY_VERSION, RunMode.DISCUSS_READ_ONLY,
                CODEX, CODEX_COMPATIBILITY,
                Collections.singleton(SkillTrustSource.PLATFORM));
    }

    private static PhaseCapabilityProfile profile(
            PhaseCapabilityReference... capabilities) {
        return PhaseCapabilityProfile.create(
                "requirement-profile", "1.0.0", PHASE,
                Arrays.asList(capabilities));
    }

    private static PhaseCapabilityReference required(
            String id, PhaseCapabilityType type) {
        return new PhaseCapabilityReference(id, type, true);
    }

    private static PhaseCapabilityReference optional(
            String id, PhaseCapabilityType type) {
        return new PhaseCapabilityReference(id, type, false);
    }

    private static RuleDefinition rule(String id, boolean mandatory) {
        return rule(id, "1.0.0", mandatory);
    }

    private static RuleDefinition rule(
            String id, String version, boolean mandatory) {
        return rule(id, version, mandatory, strings(USE_CASE));
    }

    private static RuleDefinition rule(
            String id, String version, boolean mandatory,
            Set<String> applicableUseCases) {
        String content = "Rule content for " + id + "@" + version;
        RuleResource resource = new RuleResource(
                "rule", id + "/" + version + "/rule.md", content,
                CanonicalHashing.sha256(content));
        return new RuleDefinition(
                id, version, "PLATFORM", mandatory, "Safe summary for " + id,
                applicableUseCases, Collections.singletonList(resource),
                CanonicalHashing.sha256("rule-package:" + id + ":" + version));
    }

    private static SkillPackage skill(String id) {
        return skill(id, "1.0.0");
    }

    private static SkillPackage skill(String id, String version) {
        return skill(
                id, version, SkillTrustSource.PLATFORM, strings(CODEX),
                Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet());
    }

    private static SkillPackage skillWithTags(
            String id, Set<String> technologyTags) {
        SkillManifest manifest = new SkillManifest(
                id, "1.0.0", "Skill " + id, strings(USE_CASE),
                technologyTags, Collections.<String>emptySet(),
                "SKILL.md", Collections.<String>emptySet(),
                Collections.<SkillDependency>emptyList(),
                Collections.<String>emptySet(), strings(CODEX),
                SkillTrustSource.PLATFORM,
                Collections.<CapabilityRequest>emptyList());
        return new SkillPackage(
                manifest,
                CanonicalHashing.sha256("skill-package:" + id + ":1.0.0"),
                "# Skill " + id,
                Collections.<String, String>emptyMap());
    }

    private static SkillPackage skill(
            String id, String version, SkillTrustSource trustSource,
            Set<String> runtimes, List<SkillDependency> dependencies,
            Set<String> conflicts) {
        SkillManifest manifest = new SkillManifest(
                id, version, "Skill " + id, strings(USE_CASE),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                "SKILL.md", Collections.<String>emptySet(), dependencies,
                conflicts, runtimes, trustSource,
                Collections.<CapabilityRequest>emptyList());
        return new SkillPackage(
                manifest,
                CanonicalHashing.sha256("skill-package:" + id + ":" + version),
                "# Skill " + id,
                Collections.<String, String>emptyMap());
    }

    private static McpServerDefinition mcp(
            String id, CapabilityAccess access) {
        return mcp(id, "1.0.0", access);
    }

    private static McpServerDefinition mcp(
            String id, String version, CapabilityAccess access) {
        return mcp(id, version, access, strings(CODEX));
    }

    private static McpServerDefinition mcp(
            String id, String version, CapabilityAccess access,
            Set<String> runtimes) {
        return new McpServerDefinition(
                id, version, "MCP " + id,
                strings(USE_CASE), runtimes,
                Arrays.asList("mcp-" + id, "--stdio"),
                Collections.singletonList(new McpCapability(
                        id + "/tool", McpCapabilityType.TOOL, access)),
                Collections.<McpSecretReference>emptyList(),
                10, 30,
                CanonicalHashing.sha256("mcp-definition:" + id + ":" + version));
    }

    private static MutableRuleCatalog rules(RuleDefinition... definitions) {
        return new MutableRuleCatalog(Arrays.asList(definitions));
    }

    private static MutableSkillCatalog skills(SkillPackage... packages) {
        return new MutableSkillCatalog(Arrays.asList(packages));
    }

    private static MutableMcpServerCatalog mcpServers(
            McpServerDefinition... definitions) {
        return new MutableMcpServerCatalog(Arrays.asList(definitions));
    }

    private static Set<String> strings(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    private static Set<RepositoryDevelopmentMarker> stringsOfMarkers(
            RepositoryDevelopmentMarker... values) {
        return new LinkedHashSet<RepositoryDevelopmentMarker>(
                Arrays.asList(values));
    }

    private static String repeat(char value) {
        char[] content = new char[64];
        Arrays.fill(content, value);
        return new String(content);
    }

    private static void assertRequiredUnavailable(
            CapabilityResolutionException failure) {
        assertEquals("WORKBENCH_CAPABILITY_REQUIRED_UNAVAILABLE",
                failure.getCode());
    }

    private static List<String> ruleIds(ResolvedCapabilityBinding binding) {
        List<String> ids = new ArrayList<String>();
        for (ResolvedRuleBinding rule : binding.getRules()) {
            ids.add(rule.getId());
        }
        return ids;
    }

    private static List<String> skillIds(ResolvedCapabilityBinding binding) {
        List<String> ids = new ArrayList<String>();
        for (ResolvedSkillBinding skill : binding.getSkills()) {
            ids.add(skill.getId());
        }
        return ids;
    }

    private static List<String> mcpIds(ResolvedCapabilityBinding binding) {
        List<String> ids = new ArrayList<String>();
        for (ResolvedMcpServerBinding mcp : binding.getMcpServers()) {
            ids.add(mcp.getId());
        }
        return ids;
    }

    private static ResolvedRuleBinding ruleBinding(
            ResolvedCapabilityBinding binding, String id) {
        for (ResolvedRuleBinding rule : binding.getRules()) {
            if (id.equals(rule.getId())) {
                return rule;
            }
        }
        throw new AssertionError("resolved rule not found: " + id);
    }

    private static String rejectionReason(
            ResolvedCapabilityBinding binding, String id) {
        for (RejectedCapability rejected : binding.getRejected()) {
            if (id.equals(rejected.getId())) {
                return rejected.getReasonCode();
            }
        }
        throw new AssertionError("rejection not found: " + id);
    }

    private static final class MutableRuleCatalog implements RuleCatalog {

        private final List<RuleDefinition> definitions =
                new ArrayList<RuleDefinition>();

        private MutableRuleCatalog(List<RuleDefinition> definitions) {
            this.definitions.addAll(definitions);
        }

        @Override
        public RuleDefinition resolveById(String logicalId) {
            List<RuleDefinition> matches = new ArrayList<RuleDefinition>();
            for (RuleDefinition definition : definitions) {
                if (definition.getId().equals(logicalId)) {
                    matches.add(definition);
                }
            }
            return requireSingle(matches);
        }

        private RuleDefinition requireSingle(List<RuleDefinition> matches) {
            if (matches.isEmpty()) {
                throw new CapabilityCatalogException(
                        "RULE_DEFINITION_NOT_FOUND", "rule definition is missing");
            }
            if (matches.size() > 1) {
                throw new CapabilityCatalogException(
                        "RULE_DEFINITION_VERSION_CONFLICT",
                        "rule definition identifier is duplicated");
            }
            return matches.get(0);
        }

        private void replace(RuleDefinition... replacements) {
            definitions.clear();
            definitions.addAll(Arrays.asList(replacements));
        }
    }

    private static final class MutableSkillCatalog implements SkillCatalog {

        private final List<SkillPackage> packages =
                new ArrayList<SkillPackage>();

        private MutableSkillCatalog(List<SkillPackage> packages) {
            this.packages.addAll(packages);
        }

        @Override
        public List<SkillPackage> discover() {
            return Collections.unmodifiableList(
                    new ArrayList<SkillPackage>(packages));
        }

        private void replace(SkillPackage... replacements) {
            packages.clear();
            packages.addAll(Arrays.asList(replacements));
        }
    }

    private static final class MutableMcpServerCatalog
            implements McpServerCatalog {

        private final List<McpServerDefinition> definitions =
                new ArrayList<McpServerDefinition>();

        private MutableMcpServerCatalog(
                List<McpServerDefinition> definitions) {
            this.definitions.addAll(definitions);
        }

        @Override
        public List<McpServerDefinition> discover() {
            return Collections.unmodifiableList(
                    new ArrayList<McpServerDefinition>(definitions));
        }

        private void replace(McpServerDefinition... replacements) {
            definitions.clear();
            definitions.addAll(Arrays.asList(replacements));
        }
    }
}
