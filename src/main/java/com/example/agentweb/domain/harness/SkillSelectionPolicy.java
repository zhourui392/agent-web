package com.example.agentweb.domain.harness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 信任、选择、一层依赖、冲突及能力授权领域策略。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public final class SkillSelectionPolicy {

    public SkillSelection select(CapabilitySelectionRequest request, List<SkillPackage> catalog) {
        if (request == null || catalog == null || catalog.contains(null)) {
            throw new IllegalArgumentException("selection request and catalog must not be null");
        }
        List<SkillPackage> orderedCatalog = new ArrayList<SkillPackage>(catalog);
        orderedCatalog.sort(Comparator.comparing((SkillPackage value) -> value.getManifest().getId())
                .thenComparing(value -> value.getManifest().getVersion()));
        Map<String, SelectedSkill> selected = new LinkedHashMap<String, SelectedSkill>();
        List<RejectedSkill> rejected = new ArrayList<RejectedSkill>();

        selectRequiredIds(request.getDefaultSkillIds(), SkillSelectionReason.STAGE_DEFAULT,
                request, orderedCatalog, selected);
        selectRequiredIds(request.getExplicitSkillIds(), SkillSelectionReason.USER_EXPLICIT,
                request, orderedCatalog, selected);
        selectByTags(request, orderedCatalog, selected, rejected);
        requireNoDependencyCycles(selected);
        resolveDependencies(request, orderedCatalog, selected);
        requireNoDependencyCycles(selected);
        requireNoConflicts(selected);

        List<CapabilityDecision> decisions = authorize(selected, request.getGrant(), request.getStage());
        return new SkillSelection(new ArrayList<SelectedSkill>(selected.values()), rejected, decisions);
    }

    private void selectRequiredIds(Set<String> ids, SkillSelectionReason reason,
                                   CapabilitySelectionRequest request, List<SkillPackage> catalog,
                                   Map<String, SelectedSkill> selected) {
        for (String id : ids) {
            if (selected.containsKey(id)) {
                continue;
            }
            List<SkillPackage> candidates = findById(catalog, id);
            if (candidates.isEmpty()) {
                throw failure("SKILL_NOT_FOUND", "required skill is not present in catalog: " + id);
            }
            if (candidates.size() > 1) {
                throw failure("SKILL_VERSION_CONFLICT", "multiple versions found for selected skill: " + id);
            }
            SkillPackage candidate = candidates.get(0);
            requireTrusted(candidate, request);
            requireCompatible(candidate, request);
            selected.put(id, new SelectedSkill(candidate, reason));
        }
    }

    private void selectByTags(CapabilitySelectionRequest request, List<SkillPackage> catalog,
                              Map<String, SelectedSkill> selected, List<RejectedSkill> rejected) {
        if (request.getTechnicalTags().isEmpty()) {
            return;
        }
        Map<String, List<SkillPackage>> matches = new LinkedHashMap<String, List<SkillPackage>>();
        for (SkillPackage candidate : catalog) {
            SkillManifest manifest = candidate.getManifest();
            if (!manifest.matchesAnyTag(request.getTechnicalTags()) || selected.containsKey(manifest.getId())) {
                continue;
            }
            matches.computeIfAbsent(manifest.getId(), ignored -> new ArrayList<SkillPackage>()).add(candidate);
        }
        for (Map.Entry<String, List<SkillPackage>> entry : matches.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw failure("SKILL_VERSION_CONFLICT",
                        "multiple versions found for tag-selected skill: " + entry.getKey());
            }
            SkillPackage candidate = entry.getValue().get(0);
            SkillRejectionReason rejection = rejection(candidate, request);
            if (rejection != null) {
                rejected.add(new RejectedSkill(candidate.getManifest().getId(),
                        candidate.getManifest().getVersion(), rejection));
            } else {
                selected.put(candidate.getManifest().getId(),
                        new SelectedSkill(candidate, SkillSelectionReason.TECH_TAG));
            }
        }
    }

    private void resolveDependencies(CapabilitySelectionRequest request, List<SkillPackage> catalog,
                                     Map<String, SelectedSkill> selected) {
        List<SelectedSkill> roots = new ArrayList<SelectedSkill>(selected.values());
        for (SelectedSkill root : roots) {
            SkillManifest manifest = root.getSkillPackage().getManifest();
            for (SkillDependency dependency : manifest.getDependencies()) {
                SelectedSkill existing = selected.get(dependency.getSkillId());
                if (existing != null) {
                    if (!existing.getSkillPackage().getManifest().getVersion().equals(dependency.getVersion())) {
                        throw failure("SKILL_VERSION_CONFLICT",
                                "dependency version conflicts with selected skill: " + dependency.getSkillId());
                    }
                    continue;
                }
                SkillPackage dependencyPackage = findExact(catalog, dependency);
                if (dependencyPackage == null) {
                    throw failure("SKILL_DEPENDENCY_MISSING", "required dependency is missing: "
                            + dependency.getSkillId() + "@" + dependency.getVersion());
                }
                SkillManifest dependencyManifest = dependencyPackage.getManifest();
                if (!dependencyManifest.getDependencies().isEmpty()) {
                    if (dependsOn(dependencyManifest, manifest.getId())) {
                        throw failure("SKILL_DEPENDENCY_CYCLE", "skill dependency cycle detected: "
                                + manifest.getId() + " -> " + dependencyManifest.getId());
                    }
                    throw failure("SKILL_DEPENDENCY_DEPTH_EXCEEDED",
                            "only one dependency layer is supported: " + dependencyManifest.getId());
                }
                requireTrusted(dependencyPackage, request);
                requireCompatible(dependencyPackage, request);
                selected.put(dependencyManifest.getId(),
                        new SelectedSkill(dependencyPackage, SkillSelectionReason.REQUIRED_DEPENDENCY));
            }
        }
    }

    private boolean dependsOn(SkillManifest manifest, String skillId) {
        for (SkillDependency dependency : manifest.getDependencies()) {
            if (dependency.getSkillId().equals(skillId)) {
                return true;
            }
        }
        return false;
    }

    private void requireNoConflicts(Map<String, SelectedSkill> selected) {
        Set<String> ids = new LinkedHashSet<String>(selected.keySet());
        for (SelectedSkill selectedSkill : selected.values()) {
            SkillManifest manifest = selectedSkill.getSkillPackage().getManifest();
            for (String conflict : manifest.getConflicts()) {
                if (ids.contains(conflict)) {
                    throw failure("SKILL_CONFLICT", "selected skills conflict: "
                            + manifest.getId() + " <-> " + conflict);
                }
            }
        }
    }

    private void requireNoDependencyCycles(Map<String, SelectedSkill> selected) {
        Set<String> visited = new LinkedHashSet<String>();
        for (String skillId : selected.keySet()) {
            visitDependency(skillId, selected, new LinkedHashSet<String>(), visited);
        }
    }

    private void visitDependency(String skillId, Map<String, SelectedSkill> selected,
                                 Set<String> visiting, Set<String> visited) {
        if (visiting.contains(skillId)) {
            throw failure("SKILL_DEPENDENCY_CYCLE", "skill dependency cycle detected at: " + skillId);
        }
        if (visited.contains(skillId)) {
            return;
        }
        SelectedSkill skill = selected.get(skillId);
        if (skill == null) {
            return;
        }
        visiting.add(skillId);
        for (SkillDependency dependency : skill.getSkillPackage().getManifest().getDependencies()) {
            visitDependency(dependency.getSkillId(), selected, visiting, visited);
        }
        visiting.remove(skillId);
        visited.add(skillId);
    }

    private List<CapabilityDecision> authorize(Map<String, SelectedSkill> selected, CapabilityGrant grant,
                                               HarnessStage stage) {
        List<CapabilityDecision> decisions = new ArrayList<CapabilityDecision>();
        for (SelectedSkill skill : selected.values()) {
            SkillManifest manifest = skill.getSkillPackage().getManifest();
            for (CapabilityRequest request : manifest.getCapabilityRequests()) {
                boolean explicitlyGranted = grant.permits(request);
                boolean authorized = explicitlyGranted && StageCapabilityPolicy.permits(stage, request);
                decisions.add(new CapabilityDecision(manifest.getId(), request, authorized,
                        authorized ? "EXPLICITLY_GRANTED"
                                : explicitlyGranted ? "STAGE_POLICY_DENIED" : "NOT_GRANTED"));
            }
        }
        return decisions;
    }

    private SkillRejectionReason rejection(SkillPackage candidate, CapabilitySelectionRequest request) {
        SkillManifest manifest = candidate.getManifest();
        if (manifest.getTrustSource() == SkillTrustSource.WORKSPACE
                && !request.isWorkspaceApproved(manifest.getId())) {
            return SkillRejectionReason.WORKSPACE_NOT_APPROVED;
        }
        if (!manifest.getApplicableStages().contains(request.getStage())) {
            return SkillRejectionReason.STAGE_INCOMPATIBLE;
        }
        if (!manifest.getRuntimes().contains(request.getRuntime())) {
            return SkillRejectionReason.RUNTIME_INCOMPATIBLE;
        }
        return null;
    }

    private void requireTrusted(SkillPackage candidate, CapabilitySelectionRequest request) {
        if (candidate.getManifest().getTrustSource() == SkillTrustSource.WORKSPACE
                && !request.isWorkspaceApproved(candidate.getManifest().getId())) {
            throw failure("SKILL_WORKSPACE_NOT_APPROVED",
                    "workspace skill is not approved for this run: " + candidate.getManifest().getId());
        }
    }

    private void requireCompatible(SkillPackage candidate, CapabilitySelectionRequest request) {
        SkillManifest manifest = candidate.getManifest();
        if (!manifest.getApplicableStages().contains(request.getStage())) {
            throw failure("SKILL_STAGE_INCOMPATIBLE",
                    "skill does not support stage " + request.getStage() + ": " + manifest.getId());
        }
        if (!manifest.getRuntimes().contains(request.getRuntime())) {
            throw failure("SKILL_RUNTIME_INCOMPATIBLE",
                    "skill does not support runtime " + request.getRuntime() + ": " + manifest.getId());
        }
    }

    private List<SkillPackage> findById(List<SkillPackage> catalog, String id) {
        List<SkillPackage> matches = new ArrayList<SkillPackage>();
        for (SkillPackage candidate : catalog) {
            if (candidate.getManifest().getId().equals(id)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private SkillPackage findExact(List<SkillPackage> catalog, SkillDependency dependency) {
        SkillPackage found = null;
        for (SkillPackage candidate : catalog) {
            SkillManifest manifest = candidate.getManifest();
            if (manifest.getId().equals(dependency.getSkillId())
                    && manifest.getVersion().equals(dependency.getVersion())) {
                if (found != null) {
                    throw failure("SKILL_VERSION_CONFLICT",
                            "duplicate package found for dependency: " + dependency.getSkillId());
                }
                found = candidate;
            }
        }
        return found;
    }

    private CapabilityResolutionException failure(String code, String message) {
        return new CapabilityResolutionException(code, message);
    }
}
