package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Codex 自动发现 Repo Skill 的可信 Catalog 与 Package Hash 求交测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class WorkspaceSkillTrustPolicyTest {

    private final WorkspaceSkillTrustPolicy policy = new WorkspaceSkillTrustPolicy();

    @Test
    void shouldAcceptRegisteredRepoSkillWithMatchingEntryHash() {
        String entryHash = HarnessHashing.sha256("# trusted");
        WorkspaceRuntimeInventory inventory = inventory(
                repoSkill("trusted", ".agents/skills/trusted/SKILL.md", entryHash));

        policy.requireTrusted(inventory, Collections.singletonList(
                skillPackage("trusted", "1.0.0", entryHash)));

        assertEquals(Collections.singletonList(".agents/skills/trusted/SKILL.md"),
                inventory.disabledRepoSkillPaths());
    }

    @Test
    void shouldFailClosedForUnregisteredOrChangedRepoSkill() {
        WorkspaceRuntimeInventory unregistered = inventory(
                repoSkill("unknown", ".agents/skills/unknown/SKILL.md",
                        HarnessHashing.sha256("# unknown")));
        CapabilityResolutionException missing = assertThrows(CapabilityResolutionException.class,
                () -> policy.requireTrusted(unregistered, Collections.<SkillPackage>emptyList()));

        WorkspaceRuntimeInventory changed = inventory(
                repoSkill("trusted", ".agents/skills/trusted/SKILL.md",
                        HarnessHashing.sha256("# changed")));
        CapabilityResolutionException mismatch = assertThrows(CapabilityResolutionException.class,
                () -> policy.requireTrusted(changed, Collections.singletonList(
                        skillPackage("trusted", "1.0.0", HarnessHashing.sha256("# trusted")))));

        assertEquals("REPO_SKILL_NOT_REGISTERED", missing.getCode());
        assertEquals("REPO_SKILL_HASH_MISMATCH", mismatch.getCode());
    }

    @Test
    void shouldProduceStableInventoryHashAndSortedDisablePaths() {
        WorkspaceRepoSkill alpha = repoSkill("alpha", ".agents/skills/alpha/SKILL.md",
                HarnessHashing.sha256("alpha"));
        WorkspaceRepoSkill zeta = repoSkill("zeta", ".agents/skills/zeta/SKILL.md",
                HarnessHashing.sha256("zeta"));

        WorkspaceRuntimeInventory first = new WorkspaceRuntimeInventory(
                WorkspaceBoundaryKind.GIT_ROOT, true, Arrays.asList(zeta, alpha));
        WorkspaceRuntimeInventory second = new WorkspaceRuntimeInventory(
                WorkspaceBoundaryKind.GIT_ROOT, true, Arrays.asList(alpha, zeta));

        assertEquals(first.getInventoryHash(), second.getInventoryHash());
        assertEquals(Arrays.asList(".agents/skills/alpha/SKILL.md",
                ".agents/skills/zeta/SKILL.md"), first.disabledRepoSkillPaths());
    }

    private WorkspaceRuntimeInventory inventory(WorkspaceRepoSkill skill) {
        return new WorkspaceRuntimeInventory(WorkspaceBoundaryKind.GIT_ROOT, true,
                Collections.singletonList(skill));
    }

    private WorkspaceRepoSkill repoSkill(String id, String path, String entryHash) {
        return new WorkspaceRepoSkill(id, path, entryHash);
    }

    private SkillPackage skillPackage(String id, String version, String entryHash) {
        SkillManifest manifest = new SkillManifest(id, version, id + " skill",
                EnumSet.of(HarnessStage.ANALYSIS), Collections.<String>emptySet(),
                Collections.<String>emptySet(), "SKILL.md", Collections.<String>emptySet(),
                Collections.<SkillDependency>emptyList(), Collections.<String>emptySet(),
                EnumSet.of(AgentRuntime.CODEX), SkillTrustSource.PLATFORM,
                Collections.<CapabilityRequest>emptyList());
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        hashes.put("SKILL.md", entryHash);
        return new SkillPackage(manifest, HarnessHashing.sha256(id + version),
                "# " + id, hashes);
    }
}
