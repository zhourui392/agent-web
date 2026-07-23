package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 选择、信任、依赖、冲突和授权领域规则测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class SkillSelectionPolicyTest {

    private final SkillSelectionPolicy policy = new SkillSelectionPolicy();

    @Test
    void shouldSelectDefaultExplicitAndTechnicalSkillsWithDeterministicReasons() {
        SkillPackage defaultSkill = skill("analysis-core", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags(), dependencies(), conflicts(), capabilities());
        SkillPackage explicitSkill = skill("spring-review", "1.0.0", SkillTrustSource.APPROVED_USER,
                stages(HarnessStage.ANALYSIS), tags("spring"), dependencies(), conflicts(), capabilities());
        SkillPackage taggedSkill = skill("java-review", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags("java"), dependencies(), conflicts(), capabilities());

        SkillSelection result = policy.select(request(
                ids("analysis-core"), ids("spring-review"), tags("java"), ids(), CapabilityGrant.none()),
                Arrays.asList(taggedSkill, explicitSkill, defaultSkill));

        assertEquals(Arrays.asList("analysis-core", "spring-review", "java-review"), result.selectedSkillIds());
        assertEquals(SkillSelectionReason.STAGE_DEFAULT, result.getSelected().get(0).getReason());
        assertEquals(SkillSelectionReason.USER_EXPLICIT, result.getSelected().get(1).getReason());
        assertEquals(SkillSelectionReason.TECH_TAG, result.getSelected().get(2).getReason());
    }

    @Test
    void shouldRejectUnapprovedWorkspaceSkillWithoutEnablingIt() {
        SkillPackage workspace = skill("workspace-helper", "1.0.0", SkillTrustSource.WORKSPACE,
                stages(HarnessStage.ANALYSIS), tags("java"), dependencies(), conflicts(), capabilities());

        SkillSelection result = policy.select(request(
                ids(), ids(), tags("java"), ids(), CapabilityGrant.none()),
                Collections.singletonList(workspace));

        assertTrue(result.getSelected().isEmpty());
        assertEquals(SkillRejectionReason.WORKSPACE_NOT_APPROVED,
                result.getRejected().get(0).getReason());
    }

    @Test
    void shouldEnableApprovedWorkspaceSkill() {
        SkillPackage workspace = skill("workspace-helper", "1.0.0", SkillTrustSource.WORKSPACE,
                stages(HarnessStage.ANALYSIS), tags("java"), dependencies(), conflicts(), capabilities());

        SkillSelection result = policy.select(request(
                ids(), ids("workspace-helper"), tags(), ids("workspace-helper"), CapabilityGrant.none()),
                Collections.singletonList(workspace));

        assertEquals(Collections.singletonList("workspace-helper"), result.selectedSkillIds());
    }

    @Test
    void shouldFailClosedWhenExplicitSkillDoesNotSupportStageOrRuntime() {
        SkillPackage implementationOnly = skill("implementation-only", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.IMPLEMENTATION), tags(), dependencies(), conflicts(), capabilities());

        CapabilityResolutionException stageError = assertThrows(CapabilityResolutionException.class,
                () -> policy.select(request(ids(), ids("implementation-only"), tags(), ids(), CapabilityGrant.none()),
                        Collections.singletonList(implementationOnly)));

        assertEquals("SKILL_STAGE_INCOMPATIBLE", stageError.getCode());

        SkillPackage claudeOnly = skill("claude-only", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags(), runtimes(AgentRuntime.CLAUDE),
                dependencies(), conflicts(), capabilities());
        CapabilityResolutionException runtimeError = assertThrows(CapabilityResolutionException.class,
                () -> policy.select(request(ids(), ids("claude-only"), tags(), ids(), CapabilityGrant.none()),
                        Collections.singletonList(claudeOnly)));

        assertEquals("SKILL_RUNTIME_INCOMPATIBLE", runtimeError.getCode());
    }

    @Test
    void shouldFailClosedOnSelectedSkillVersionConflict() {
        SkillPackage v1 = skill("java-review", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags("java"), dependencies(), conflicts(), capabilities());
        SkillPackage v2 = skill("java-review", "2.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags("java"), dependencies(), conflicts(), capabilities());

        CapabilityResolutionException error = assertThrows(CapabilityResolutionException.class,
                () -> policy.select(request(ids(), ids(), tags("java"), ids(), CapabilityGrant.none()),
                        Arrays.asList(v1, v2)));

        assertEquals("SKILL_VERSION_CONFLICT", error.getCode());
    }

    @Test
    void shouldResolveExactlyOneDependencyLayer() {
        SkillPackage dependency = skill("base", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags(), dependencies(), conflicts(), capabilities());
        SkillPackage parent = skill("analysis-core", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags(),
                dependencies(new SkillDependency("base", "1.0.0")), conflicts(), capabilities());

        SkillSelection result = policy.select(request(
                ids("analysis-core"), ids(), tags(), ids(), CapabilityGrant.none()),
                Arrays.asList(parent, dependency));

        assertEquals(Arrays.asList("analysis-core", "base"), result.selectedSkillIds());
        assertEquals(SkillSelectionReason.REQUIRED_DEPENDENCY, result.getSelected().get(1).getReason());
    }

    @Test
    void shouldFailClosedOnMissingOrCircularDependency() {
        SkillPackage missing = skill("analysis-core", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags(),
                dependencies(new SkillDependency("missing", "1.0.0")), conflicts(), capabilities());

        CapabilityResolutionException missingError = assertThrows(CapabilityResolutionException.class,
                () -> policy.select(request(ids("analysis-core"), ids(), tags(), ids(), CapabilityGrant.none()),
                        Collections.singletonList(missing)));
        assertEquals("SKILL_DEPENDENCY_MISSING", missingError.getCode());

        SkillPackage first = skill("first", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags(),
                dependencies(new SkillDependency("second", "1.0.0")), conflicts(), capabilities());
        SkillPackage second = skill("second", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags(),
                dependencies(new SkillDependency("first", "1.0.0")), conflicts(), capabilities());

        CapabilityResolutionException circularError = assertThrows(CapabilityResolutionException.class,
                () -> policy.select(request(ids("first"), ids(), tags(), ids(), CapabilityGrant.none()),
                        Arrays.asList(first, second)));
        assertEquals("SKILL_DEPENDENCY_CYCLE", circularError.getCode());

        CapabilityResolutionException bothSelectedCycle = assertThrows(CapabilityResolutionException.class,
                () -> policy.select(request(ids("first", "second"), ids(), tags(), ids(), CapabilityGrant.none()),
                        Arrays.asList(first, second)));
        assertEquals("SKILL_DEPENDENCY_CYCLE", bothSelectedCycle.getCode());
    }

    @Test
    void shouldFailClosedWhenSelectedSkillsConflict() {
        SkillPackage first = skill("first", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags(), dependencies(), conflicts("second"), capabilities());
        SkillPackage second = skill("second", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags(), dependencies(), conflicts(), capabilities());

        CapabilityResolutionException error = assertThrows(CapabilityResolutionException.class,
                () -> policy.select(request(ids("first", "second"), ids(), tags(), ids(), CapabilityGrant.none()),
                        Arrays.asList(first, second)));

        assertEquals("SKILL_CONFLICT", error.getCode());
    }

    @Test
    void shouldNotAuthorizeCommandMerelyBecauseSkillWasSelected() {
        CapabilityRequest command = CapabilityRequest.command("mvn-test");
        CapabilityRequest read = CapabilityRequest.fileRead("workspace");
        SkillPackage skill = skill("analysis-core", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.ANALYSIS), tags(), dependencies(), conflicts(),
                capabilities(command, read));

        SkillSelection denied = policy.select(request(
                ids("analysis-core"), ids(), tags(), ids(), CapabilityGrant.none()),
                Collections.singletonList(skill));

        assertFalse(denied.getCapabilityDecisions().get(0).isAuthorized());
        assertFalse(denied.getCapabilityDecisions().get(1).isAuthorized());

        CapabilityGrant grant = new CapabilityGrant(ids("workspace"), ids(), ids("mvn-test"));
        SkillSelection authorized = policy.select(request(
                ids("analysis-core"), ids(), tags(), ids(), grant), Collections.singletonList(skill));

        assertFalse(authorized.getCapabilityDecisions().get(0).isAuthorized());
        assertEquals("STAGE_POLICY_DENIED", authorized.getCapabilityDecisions().get(0).getReason());
        assertTrue(authorized.getCapabilityDecisions().get(1).isAuthorized());
    }

    @Test
    void shouldIntersectExplicitGrantWithImplementationStageCommandPolicy() {
        CapabilityRequest testCommand = CapabilityRequest.command("mvn-test");
        CapabilityRequest deployCommand = CapabilityRequest.command("mvn-verify");
        SkillPackage implementation = skill("java-tdd", "1.0.0", SkillTrustSource.PLATFORM,
                stages(HarnessStage.IMPLEMENTATION), tags(), dependencies(), conflicts(),
                capabilities(testCommand, deployCommand));
        CapabilityGrant grants = new CapabilityGrant(ids(), ids(), ids("mvn-test", "mvn-verify"));
        CapabilitySelectionRequest request = new CapabilitySelectionRequest(
                HarnessStage.IMPLEMENTATION, AgentRuntime.CODEX, ids("java-tdd"), ids(), tags(), ids(), grants);

        SkillSelection result = policy.select(request, Collections.singletonList(implementation));

        assertTrue(result.getCapabilityDecisions().get(0).isAuthorized());
        assertFalse(result.getCapabilityDecisions().get(1).isAuthorized());
        assertEquals("STAGE_POLICY_DENIED", result.getCapabilityDecisions().get(1).getReason());
    }

    private CapabilitySelectionRequest request(Set<String> defaults, Set<String> explicit,
                                               Set<String> techTags, Set<String> approvedWorkspace,
                                               CapabilityGrant grant) {
        return new CapabilitySelectionRequest(HarnessStage.ANALYSIS, AgentRuntime.CODEX,
                defaults, explicit, techTags, approvedWorkspace, grant);
    }

    private SkillPackage skill(String id, String version, SkillTrustSource trust,
                               Set<HarnessStage> stages, Set<String> tags,
                               List<SkillDependency> dependencies, Set<String> conflicts,
                               List<CapabilityRequest> requests) {
        return skill(id, version, trust, stages, tags, runtimes(AgentRuntime.CODEX),
                dependencies, conflicts, requests);
    }

    private SkillPackage skill(String id, String version, SkillTrustSource trust,
                               Set<HarnessStage> stages, Set<String> tags,
                               Set<AgentRuntime> runtimes, List<SkillDependency> dependencies,
                               Set<String> conflicts, List<CapabilityRequest> requests) {
        SkillManifest manifest = new SkillManifest(id, version, id + " description", stages, tags,
                ids(), "SKILL.md", ids(), dependencies, conflicts, runtimes, trust, requests);
        return new SkillPackage(manifest, repeat('a', 64), "# " + id, Collections.<String, String>emptyMap());
    }

    private Set<HarnessStage> stages(HarnessStage... values) {
        return values.length == 0 ? EnumSet.noneOf(HarnessStage.class) : EnumSet.copyOf(Arrays.asList(values));
    }

    private Set<AgentRuntime> runtimes(AgentRuntime... values) {
        return EnumSet.copyOf(Arrays.asList(values));
    }

    private Set<String> tags(String... values) {
        return ids(values);
    }

    private Set<String> ids(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    private List<SkillDependency> dependencies(SkillDependency... values) {
        return Arrays.asList(values);
    }

    private Set<String> conflicts(String... values) {
        return ids(values);
    }

    private List<CapabilityRequest> capabilities(CapabilityRequest... values) {
        return Arrays.asList(values);
    }

    private String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
