package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.CapabilityResolutionException;
import com.example.agentweb.domain.capability.McpCapability;
import com.example.agentweb.domain.capability.McpCapabilityType;
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
import com.example.agentweb.domain.shared.CanonicalHashing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 将 Phase 默认请求、当前 Override 与可信 Catalog 求交为不可变能力绑定。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class PhaseCapabilityBindingResolver {

    private static final String REQUIRED_UNAVAILABLE =
            "WORKBENCH_CAPABILITY_REQUIRED_UNAVAILABLE";
    private static final String DUPLICATE_ID = "CAPABILITY_ID_DUPLICATE";
    private static final String CATALOG_INVALID = "CAPABILITY_CATALOG_INVALID";
    private static final String TRANSPORT_STDIO = "STDIO";
    private static final String OWNER_OVERRIDE_RULE_PREFIX =
            "workbench/owner-override/";
    private static final String OWNER_OVERRIDE_RULE_SOURCE =
            "WORKBENCH_OWNER_OVERRIDE";

    private final RuleCatalog ruleCatalog;
    private final SkillCatalog skillCatalog;
    private final McpServerCatalog mcpServerCatalog;

    public PhaseCapabilityBindingResolver(
            RuleCatalog ruleCatalog, SkillCatalog skillCatalog,
            McpServerCatalog mcpServerCatalog) {
        if (ruleCatalog == null || skillCatalog == null
                || mcpServerCatalog == null) {
            throw new IllegalArgumentException(
                    "capability catalogs must not be null");
        }
        this.ruleCatalog = ruleCatalog;
        this.skillCatalog = skillCatalog;
        this.mcpServerCatalog = mcpServerCatalog;
    }

    public ResolvedCapabilityBinding resolve(
            PhaseCapabilityProfile profile, CapabilityOverride override,
            PhaseCapabilityResolutionPolicy policy) {
        return resolveForRun(profile, override, policy).getBinding();
    }

    public ResolvedCapabilityResolution resolveForRun(
            PhaseCapabilityProfile profile, CapabilityOverride override,
            PhaseCapabilityResolutionPolicy policy) {
        if (profile == null || override == null || policy == null) {
            throw new IllegalArgumentException(
                    "capability profile, override and policy must not be null");
        }
        profile.getOverridePolicy().requireAllowed(profile.getPhase(), override);
        String useCase = profile.getPhase().name();
        Map<String, SkillPackage> skillPackages = indexSkills(discoverSkills());
        Map<String, McpServerDefinition> mcpServers = indexMcpServers(
                discoverMcpServers());
        List<ResolvedRuleBinding> rules = new ArrayList<ResolvedRuleBinding>();
        List<ResolvedCapabilityRuleContent> ruleContents =
                new ArrayList<ResolvedCapabilityRuleContent>();
        List<ResolvedSkillBinding> skills = new ArrayList<ResolvedSkillBinding>();
        List<ResolvedMcpServerBinding> mcpBindings =
                new ArrayList<ResolvedMcpServerBinding>();
        List<RejectedCapability> rejected =
                new ArrayList<RejectedCapability>();

        resolveRules(
                profile, override, useCase, rules, ruleContents, rejected);
        resolveAdditionalOwnerRule(override, rules, ruleContents);
        resolveSkills(
                profile, override, policy, useCase,
                skillPackages, skills, rejected);
        resolveMcpServers(
                profile, override, policy, useCase,
                mcpServers, mcpBindings, rejected);

        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                policy.getPolicyVersion(),
                profile.getProfileId(), profile.getProfileVersion(),
                profile.getProfileHash(), rules, skills, mcpBindings,
                rejected, policy.getRuntimeCompatibility());
        return ResolvedCapabilityResolution.of(binding, ruleContents);
    }

    private void resolveAdditionalOwnerRule(
            CapabilityOverride override,
            List<ResolvedRuleBinding> resolved,
            List<ResolvedCapabilityRuleContent> contents) {
        AdditionalCapabilityRule additionalRule = override.getAdditionalRule();
        if (additionalRule == null) {
            return;
        }
        String contentHash = CanonicalHashing.sha256(
                additionalRule.getValue());
        ResolvedRuleBinding binding = new ResolvedRuleBinding(
                OWNER_OVERRIDE_RULE_PREFIX + contentHash,
                "1", OWNER_OVERRIDE_RULE_SOURCE, contentHash, false,
                "Owner-provided Workbench phase preference");
        resolved.add(binding);
        contents.add(ResolvedCapabilityRuleContent.bind(
                binding, additionalRule.getValue()));
    }

    private void resolveRules(
            PhaseCapabilityProfile profile, CapabilityOverride override,
            String useCase, List<ResolvedRuleBinding> resolved,
            List<ResolvedCapabilityRuleContent> contents,
            List<RejectedCapability> rejected) {
        Set<String> selectedOptional = override.getSelectedOptionalRuleIds();
        for (PhaseCapabilityReference reference : profile.getCapabilities()) {
            if (reference.getType() != PhaseCapabilityType.RULE) {
                continue;
            }
            boolean selectedByProfileAndOverride = reference.isRequired()
                    || selectedOptional.isEmpty()
                    || selectedOptional.contains(reference.getId());
            RuleDefinition definition = findRule(reference.getId());
            if (definition == null) {
                if (selectedByProfileAndOverride) {
                    rejectOrThrow(
                            reference.getId(), reference.isRequired(),
                            "OPTIONAL_RULE_UNAVAILABLE", rejected);
                }
                continue;
            }
            requireMatchingId(
                    reference.getId(), definition.getId(), "rule");
            boolean mandatory = reference.isRequired()
                    || definition.isMandatory();
            if (!mandatory && !selectedByProfileAndOverride) {
                continue;
            }
            if (!definition.supports(useCase)) {
                rejectOrThrow(
                        reference.getId(), mandatory,
                        "RULE_USE_CASE_INCOMPATIBLE", rejected);
                continue;
            }
            RuleResource resource = requireRuleResource(definition);
            ResolvedRuleBinding binding = new ResolvedRuleBinding(
                    definition.getId(), definition.getVersion(),
                    definition.getSource(), resource.getContentHash(),
                    mandatory, definition.getSummary());
            resolved.add(binding);
            contents.add(ResolvedCapabilityRuleContent.bind(
                    binding, resource.getContent()));
        }
    }

    private RuleResource requireRuleResource(RuleDefinition definition) {
        try {
            return definition.requireResource("rule");
        } catch (IllegalArgumentException failure) {
            throw new CapabilityResolutionException(
                    CATALOG_INVALID,
                    "rule catalog definition is missing its exact prompt resource");
        }
    }

    private RuleDefinition findRule(String id) {
        try {
            RuleDefinition definition = ruleCatalog.resolveById(id);
            if (definition == null) {
                throw catalogInvalid("rule catalog returned null definition");
            }
            return definition;
        } catch (CapabilityCatalogException failure) {
            if ("RULE_DEFINITION_NOT_FOUND".equals(failure.getCode())) {
                return null;
            }
            if ("RULE_DEFINITION_VERSION_CONFLICT".equals(failure.getCode())) {
                throw duplicate(id, "rule");
            }
            throw catalogFailure(failure, "rule");
        }
    }

    private void resolveSkills(
            PhaseCapabilityProfile profile, CapabilityOverride override,
            PhaseCapabilityResolutionPolicy policy, String useCase,
            Map<String, SkillPackage> available,
            List<ResolvedSkillBinding> resolved,
            List<RejectedCapability> rejected) {
        Map<String, SkillCandidate> candidates =
                new TreeMap<String, SkillCandidate>();
        for (PhaseCapabilityReference reference : profile.getCapabilities()) {
            if (reference.getType() != PhaseCapabilityType.SKILL
                    || !isSkillSelected(reference, override)) {
                continue;
            }
            SkillPackage skillPackage = available.get(reference.getId());
            if (skillPackage == null) {
                rejectOrThrow(
                        reference.getId(), reference.isRequired(),
                        "OPTIONAL_SKILL_UNAVAILABLE", rejected);
                continue;
            }
            SkillManifest manifest = skillPackage.getManifest();
            requireMatchingId(reference.getId(), manifest.getId(), "skill");
            if (!policy.allowsSkillTrustSource(manifest.getTrustSource())) {
                rejectOrThrow(
                        reference.getId(), reference.isRequired(),
                        "SKILL_TRUST_DENIED", rejected);
                continue;
            }
            if (!manifest.supports(useCase, policy.getRuntime())) {
                rejectOrThrow(
                        reference.getId(), reference.isRequired(),
                        "RUNTIME_INCOMPATIBLE", rejected);
                continue;
            }
            candidates.put(reference.getId(),
                    new SkillCandidate(reference, skillPackage));
        }

        resolveDependencies(
                candidates, available,
                override.getRemovedOptionalSkillIds(), policy, useCase);
        rejectConflicts(candidates);
        rejectUnavailableDependencies(candidates);
        Set<String> activeSkillIds = activeSkillIds(candidates);
        for (SkillCandidate candidate : candidates.values()) {
            if (candidate.getRejectionReason() != null) {
                if (candidate.isProfileRoot()) {
                    rejectOrThrow(
                            candidate.getId(),
                            candidate.getReference().isRequired(),
                            candidate.getRejectionReason(), rejected);
                }
                continue;
            }
            if (!activeSkillIds.contains(candidate.getId())) {
                continue;
            }
            SkillManifest manifest = candidate.getSkillPackage().getManifest();
            resolved.add(new ResolvedSkillBinding(
                    manifest.getId(), manifest.getVersion(),
                    manifest.getTrustSource().name(),
                    candidate.getSkillPackage().getPackageHash(),
                    manifest.getTrustSource().name()));
        }
    }

    private boolean isSkillSelected(
            PhaseCapabilityReference reference,
            CapabilityOverride override) {
        if (reference.isRequired()) {
            return true;
        }
        if (override.getRemovedOptionalSkillIds()
                .contains(reference.getId())) {
            return false;
        }
        return true;
    }

    private void resolveDependencies(
            Map<String, SkillCandidate> candidates,
            Map<String, SkillPackage> available,
            Set<String> removedOptionalSkillIds,
            PhaseCapabilityResolutionPolicy policy,
            String useCase) {
        Set<String> visiting = new TreeSet<String>();
        Set<String> visited = new TreeSet<String>();
        List<SkillCandidate> roots =
                new ArrayList<SkillCandidate>(candidates.values());
        for (SkillCandidate root : roots) {
            resolveDependencies(
                    root, candidates, available, removedOptionalSkillIds,
                    policy, useCase, visiting, visited);
        }
    }

    private void resolveDependencies(
            SkillCandidate candidate,
            Map<String, SkillCandidate> candidates,
            Map<String, SkillPackage> available,
            Set<String> removedOptionalSkillIds,
            PhaseCapabilityResolutionPolicy policy,
            String useCase,
            Set<String> visiting,
            Set<String> visited) {
        String candidateId = candidate.getId();
        if (visiting.contains(candidateId)) {
            throw new CapabilityResolutionException(
                    "SKILL_DEPENDENCY_CYCLE",
                    "skill dependency cycle detected");
        }
        if (visited.contains(candidateId)) {
            return;
        }
        visiting.add(candidateId);
        for (SkillDependency dependency
                : candidate.getSkillPackage().getManifest().getDependencies()) {
            SkillCandidate resolvedDependency = resolveDependency(
                    dependency, candidates, available,
                    removedOptionalSkillIds, policy, useCase);
            if (resolvedDependency == null) {
                candidate.reject("SKILL_DEPENDENCY_UNAVAILABLE");
                continue;
            }
            resolveDependencies(
                    resolvedDependency, candidates, available,
                    removedOptionalSkillIds, policy, useCase,
                    visiting, visited);
            if (resolvedDependency.getRejectionReason() != null) {
                candidate.reject("SKILL_DEPENDENCY_UNAVAILABLE");
            }
        }
        visiting.remove(candidateId);
        visited.add(candidateId);
    }

    private SkillCandidate resolveDependency(
            SkillDependency dependency,
            Map<String, SkillCandidate> candidates,
            Map<String, SkillPackage> available,
            Set<String> removedOptionalSkillIds,
            PhaseCapabilityResolutionPolicy policy,
            String useCase) {
        if (removedOptionalSkillIds.contains(dependency.getSkillId())) {
            return null;
        }
        SkillPackage dependencyPackage = available.get(dependency.getSkillId());
        if (dependencyPackage == null) {
            return null;
        }
        SkillManifest manifest = dependencyPackage.getManifest();
        if (!dependency.getVersion().equals(manifest.getVersion())
                || !policy.allowsSkillTrustSource(manifest.getTrustSource())
                || !manifest.supports(useCase, policy.getRuntime())) {
            return null;
        }
        SkillCandidate existing = candidates.get(dependency.getSkillId());
        if (existing != null) {
            return dependency.getVersion().equals(
                    existing.getSkillPackage().getManifest().getVersion())
                    ? existing : null;
        }
        SkillCandidate automatic = new SkillCandidate(null, dependencyPackage);
        candidates.put(dependency.getSkillId(), automatic);
        return automatic;
    }

    private void rejectUnavailableDependencies(
            Map<String, SkillCandidate> candidates) {
        boolean changed;
        do {
            changed = false;
            for (SkillCandidate candidate : candidates.values()) {
                if (candidate.getRejectionReason() != null) {
                    continue;
                }
                for (SkillDependency dependency
                        : candidate.getSkillPackage().getManifest()
                        .getDependencies()) {
                    SkillCandidate resolvedDependency =
                            candidates.get(dependency.getSkillId());
                    if (resolvedDependency == null
                            || resolvedDependency.getRejectionReason() != null
                            || !dependency.getVersion().equals(
                            resolvedDependency.getSkillPackage()
                                    .getManifest().getVersion())) {
                        candidate.reject("SKILL_DEPENDENCY_UNAVAILABLE");
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
    }

    private void rejectConflicts(Map<String, SkillCandidate> candidates) {
        Set<String> conflicted = new TreeSet<String>();
        for (SkillCandidate candidate : candidates.values()) {
            if (candidate.getRejectionReason() != null) {
                continue;
            }
            for (String conflictingId
                    : candidate.getSkillPackage().getManifest().getConflicts()) {
                SkillCandidate conflicting = candidates.get(conflictingId);
                if (conflicting != null
                        && conflicting.getRejectionReason() == null) {
                    conflicted.add(candidate.getId());
                    conflicted.add(conflictingId);
                }
            }
        }
        for (String id : conflicted) {
            candidates.get(id).reject("SKILL_CONFLICT");
        }
    }

    private Set<String> activeSkillIds(
            Map<String, SkillCandidate> candidates) {
        Set<String> active = new TreeSet<String>();
        for (SkillCandidate candidate : candidates.values()) {
            if (candidate.isProfileRoot()
                    && candidate.getRejectionReason() == null) {
                collectActiveSkills(candidate, candidates, active);
            }
        }
        return active;
    }

    private void collectActiveSkills(
            SkillCandidate candidate,
            Map<String, SkillCandidate> candidates,
            Set<String> active) {
        if (candidate.getRejectionReason() != null
                || !active.add(candidate.getId())) {
            return;
        }
        for (SkillDependency dependency
                : candidate.getSkillPackage().getManifest().getDependencies()) {
            SkillCandidate resolvedDependency =
                    candidates.get(dependency.getSkillId());
            if (resolvedDependency != null
                    && dependency.getVersion().equals(
                    resolvedDependency.getSkillPackage()
                            .getManifest().getVersion())) {
                collectActiveSkills(resolvedDependency, candidates, active);
            }
        }
    }

    private void resolveMcpServers(
            PhaseCapabilityProfile profile, CapabilityOverride override,
            PhaseCapabilityResolutionPolicy policy, String useCase,
            Map<String, McpServerDefinition> available,
            List<ResolvedMcpServerBinding> resolved,
            List<RejectedCapability> rejected) {
        for (PhaseCapabilityReference reference : profile.getCapabilities()) {
            if (reference.getType() != PhaseCapabilityType.MCP_SERVER
                    || !override.includesMcp(reference)) {
                continue;
            }
            McpServerDefinition definition = available.get(reference.getId());
            if (definition == null) {
                rejectOrThrow(
                        reference.getId(), reference.isRequired(),
                        "OPTIONAL_MCP_UNAVAILABLE", rejected);
                continue;
            }
            requireMatchingId(
                    reference.getId(), definition.getId(), "MCP server");
            if (!definition.getApplicableUseCases().contains(useCase)) {
                rejectOrThrow(
                        reference.getId(), reference.isRequired(),
                        "MCP_USE_CASE_INCOMPATIBLE", rejected);
                continue;
            }
            if (!definition.getCompatibleRuntimes()
                    .contains(policy.getRuntime())) {
                rejectOrThrow(
                        reference.getId(), reference.isRequired(),
                        "RUNTIME_INCOMPATIBLE", rejected);
                continue;
            }
            if (definition.hasUnsupportedResourceCapability()) {
                rejectOrThrow(
                        reference.getId(), reference.isRequired(),
                        "MCP_CAPABILITY_UNSUPPORTED", rejected);
                continue;
            }
            CapabilityAccess access = maximumAllowedMcpAccess(
                    definition, policy);
            if (access == null) {
                rejectOrThrow(
                        reference.getId(), reference.isRequired(),
                        "MCP_ACCESS_DENIED", rejected);
                continue;
            }
            resolved.add(new ResolvedMcpServerBinding(
                    definition.getId(), definition.getVersion(),
                    definition.getConfigurationHash(), access,
                    TRANSPORT_STDIO));
        }
    }

    private CapabilityAccess maximumAllowedMcpAccess(
            McpServerDefinition definition,
            PhaseCapabilityResolutionPolicy policy) {
        boolean readable = false;
        boolean writable = false;
        for (McpCapability capability : definition.getCapabilities()) {
            if (capability.getType() != McpCapabilityType.TOOL) {
                continue;
            }
            if (capability.getAccess() == CapabilityAccess.READ) {
                readable = true;
            } else if (capability.getAccess() == CapabilityAccess.WRITE) {
                writable = true;
            }
        }
        if (writable && policy.allowsMcpAccess(CapabilityAccess.WRITE)) {
            return CapabilityAccess.WRITE;
        }
        if (readable && policy.allowsMcpAccess(CapabilityAccess.READ)) {
            return CapabilityAccess.READ;
        }
        return null;
    }

    private List<SkillPackage> discoverSkills() {
        try {
            List<SkillPackage> discovered = skillCatalog.discover();
            if (discovered == null || discovered.contains(null)) {
                throw catalogInvalid(
                        "skill catalog returned null collection or entry");
            }
            return Collections.unmodifiableList(
                    new ArrayList<SkillPackage>(discovered));
        } catch (CapabilityCatalogException failure) {
            throw catalogFailure(failure, "skill");
        }
    }

    private List<McpServerDefinition> discoverMcpServers() {
        try {
            List<McpServerDefinition> discovered = mcpServerCatalog.discover();
            if (discovered == null || discovered.contains(null)) {
                throw catalogInvalid(
                        "MCP catalog returned null collection or entry");
            }
            return Collections.unmodifiableList(
                    new ArrayList<McpServerDefinition>(discovered));
        } catch (CapabilityCatalogException failure) {
            throw catalogFailure(failure, "MCP");
        }
    }

    private Map<String, SkillPackage> indexSkills(
            List<SkillPackage> packages) {
        Map<String, SkillPackage> indexed =
                new TreeMap<String, SkillPackage>();
        for (SkillPackage skillPackage : packages) {
            String id = skillPackage.getManifest().getId();
            if (indexed.put(id, skillPackage) != null) {
                throw duplicate(id, "skill");
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private Map<String, McpServerDefinition> indexMcpServers(
            List<McpServerDefinition> definitions) {
        Map<String, McpServerDefinition> indexed =
                new TreeMap<String, McpServerDefinition>();
        for (McpServerDefinition definition : definitions) {
            if (indexed.put(definition.getId(), definition) != null) {
                throw duplicate(definition.getId(), "MCP server");
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private void rejectOrThrow(
            String id, boolean required, String optionalReason,
            List<RejectedCapability> rejected) {
        if (required) {
            throw new CapabilityResolutionException(
                    REQUIRED_UNAVAILABLE,
                    "required capability is unavailable: " + id);
        }
        rejected.add(new RejectedCapability(id, optionalReason));
    }

    private void requireMatchingId(
            String expected, String actual, String kind) {
        if (!expected.equals(actual)) {
            throw catalogInvalid(
                    kind + " catalog returned a mismatched identifier");
        }
    }

    private CapabilityResolutionException duplicate(
            String id, String kind) {
        return new CapabilityResolutionException(
                DUPLICATE_ID,
                kind + " catalog contains duplicate id: " + id);
    }

    private CapabilityResolutionException catalogFailure(
            CapabilityCatalogException failure, String kind) {
        return new CapabilityResolutionException(
                failure.getCode(),
                kind + " catalog could not be resolved");
    }

    private CapabilityResolutionException catalogInvalid(String message) {
        return new CapabilityResolutionException(CATALOG_INVALID, message);
    }

    private static final class SkillCandidate {

        private final PhaseCapabilityReference reference;
        private final SkillPackage skillPackage;
        private String rejectionReason;

        private SkillCandidate(
                PhaseCapabilityReference reference,
                SkillPackage skillPackage) {
            this.reference = reference;
            this.skillPackage = skillPackage;
        }

        private PhaseCapabilityReference getReference() {
            return reference;
        }

        private SkillPackage getSkillPackage() {
            return skillPackage;
        }

        private String getId() {
            return skillPackage.getManifest().getId();
        }

        private boolean isProfileRoot() {
            return reference != null;
        }

        private String getRejectionReason() {
            return rejectionReason;
        }

        private void reject(String reason) {
            if (rejectionReason == null) {
                rejectionReason = reason;
            }
        }
    }
}
