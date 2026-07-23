package com.example.agentweb.domain.harness;

import java.util.List;

/**
 * Codex 自动发现 Repo Skill 与可信 Skill Catalog 的失败关闭求交策略。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public final class WorkspaceSkillTrustPolicy {

    public void requireTrusted(WorkspaceRuntimeInventory inventory, List<SkillPackage> catalog) {
        if (inventory == null || catalog == null || catalog.contains(null)) {
            throw new IllegalArgumentException(
                    "workspace inventory and skill catalog must not be null");
        }
        for (WorkspaceRepoSkill repoSkill : inventory.getRepoSkills()) {
            boolean registered = false;
            boolean hashMatched = false;
            for (SkillPackage skillPackage : catalog) {
                SkillManifest manifest = skillPackage.getManifest();
                if (!manifest.getId().equals(repoSkill.getId())) {
                    continue;
                }
                registered = true;
                String catalogEntryHash = skillPackage.getResourceHashes()
                        .get(manifest.getEntryPath());
                if (repoSkill.getEntryHash().equals(catalogEntryHash)) {
                    hashMatched = true;
                }
            }
            if (!registered) {
                throw failure("REPO_SKILL_NOT_REGISTERED",
                        "repo skill is not present in trusted catalog: " + repoSkill.getId());
            }
            if (!hashMatched) {
                throw failure("REPO_SKILL_HASH_MISMATCH",
                        "repo skill entry hash does not match trusted catalog: "
                                + repoSkill.getId());
            }
        }
    }

    private CapabilityResolutionException failure(String code, String message) {
        return new CapabilityResolutionException(code, message);
    }
}
