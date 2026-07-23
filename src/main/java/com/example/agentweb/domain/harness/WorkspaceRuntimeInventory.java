package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Runtime Preflight 固化的非敏感工作区配置与 Repo Skill 清单。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class WorkspaceRuntimeInventory {

    private final WorkspaceBoundaryKind boundaryKind;
    private final boolean projectConfigAbsent;
    private final List<WorkspaceRepoSkill> repoSkills;
    private final String inventoryHash;

    public WorkspaceRuntimeInventory(WorkspaceBoundaryKind boundaryKind,
                                     boolean projectConfigAbsent,
                                     List<WorkspaceRepoSkill> repoSkills) {
        if (boundaryKind == null || repoSkills == null || repoSkills.contains(null)) {
            throw new IllegalArgumentException(
                    "workspace boundary and repo skill inventory must not be null");
        }
        this.boundaryKind = boundaryKind;
        this.projectConfigAbsent = projectConfigAbsent;
        List<WorkspaceRepoSkill> ordered = new ArrayList<WorkspaceRepoSkill>(repoSkills);
        ordered.sort(Comparator.comparing(WorkspaceRepoSkill::getRelativeEntryPath)
                .thenComparing(WorkspaceRepoSkill::getId));
        this.repoSkills = Collections.unmodifiableList(ordered);
        this.inventoryHash = computeHash();
    }

    public static WorkspaceRuntimeInventory empty() {
        return new WorkspaceRuntimeInventory(WorkspaceBoundaryKind.APPROVED_ROOT, true,
                Collections.<WorkspaceRepoSkill>emptyList());
    }

    public List<String> disabledRepoSkillPaths() {
        List<String> paths = new ArrayList<String>();
        for (WorkspaceRepoSkill skill : repoSkills) {
            paths.add(skill.getRelativeEntryPath());
        }
        return Collections.unmodifiableList(paths);
    }

    private String computeHash() {
        StringBuilder canonical = new StringBuilder();
        HarnessHashing.appendFramed(canonical, "boundaryKind", boundaryKind);
        HarnessHashing.appendFramed(canonical, "projectConfigAbsent", projectConfigAbsent);
        for (WorkspaceRepoSkill skill : repoSkills) {
            HarnessHashing.appendFramed(canonical, "repoSkillId", skill.getId());
            HarnessHashing.appendFramed(canonical, "repoSkillPath", skill.getRelativeEntryPath());
            HarnessHashing.appendFramed(canonical, "repoSkillEntryHash", skill.getEntryHash());
        }
        return HarnessHashing.sha256(canonical.toString());
    }
}
