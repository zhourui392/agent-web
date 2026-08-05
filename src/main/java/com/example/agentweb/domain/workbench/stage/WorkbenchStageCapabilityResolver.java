package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityArtifactRegistry;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.CommandResolutionException;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedCommandBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.capability.SkillDependency;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.ResolvedCapabilityResolution;
import com.example.agentweb.domain.workbench.ResolvedCapabilityRuleContent;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从冻结 Stage Snapshot 和不可变 Artifact Registry 解析单次 Run 能力。
 *
 * @author alex
 * @since 2026-08-05
 */
public final class WorkbenchStageCapabilityResolver {

    private static final String CAPABILITY_POLICY_VERSION =
            "workbench-stage-policy@1";
    private static final String STAGE_RULE_SOURCE =
            "WORKBENCH_STAGE_SNAPSHOT";
    private static final String ARTIFACT_SOURCE =
            "WORKBENCH_STAGE_ARTIFACT";

    private final CapabilityArtifactRegistry artifactRegistry;

    public WorkbenchStageCapabilityResolver(
            CapabilityArtifactRegistry artifactRegistry) {
        this.artifactRegistry = Objects.requireNonNull(
                artifactRegistry, "Capability Artifact Registry");
    }

    public ResolvedCapabilityResolution resolve(
            WorkbenchStageSnapshot stageSnapshot, RunMode runMode,
            AgentType agentType, String runtimeCompatibility) {
        if (stageSnapshot == null || runMode == null || agentType == null
                || runtimeCompatibility == null) {
            throw new IllegalArgumentException(
                    "Stage capability resolution facts are required");
        }
        stageSnapshot.requireRunModeAllowed(runMode);
        requireSupportedAgent(agentType);

        ResolvedRuleBinding stageRule = stageRule(stageSnapshot);
        List<SkillPackage> skillPackages = resolveSkillPackages(
                stageSnapshot, agentType);
        List<ResolvedSkillBinding> skills = skillBindings(skillPackages);
        List<ResolvedMcpServerBinding> mcpServers = resolveMcpServers(
                stageSnapshot, runMode, agentType);
        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                CAPABILITY_POLICY_VERSION,
                "workbench-stage/" + stageSnapshot.getDefinitionIdentifier(),
                String.valueOf(stageSnapshot.getDefinitionRevision()),
                stageSnapshot.getSnapshotHash(),
                Collections.singletonList(stageRule), skills, mcpServers,
                Collections.<RejectedCapability>emptyList(),
                runtimeCompatibility);
        return ResolvedCapabilityResolution.of(
                binding, Collections.singletonList(
                        ResolvedCapabilityRuleContent.bind(
                                stageRule, stageSnapshot.getStageRules())));
    }

    public ResolvedCommandBinding resolveCommand(
            WorkbenchStageSnapshot stageSnapshot,
            WorkbenchStageCommandInvocation invocation) {
        if (stageSnapshot == null) {
            throw new IllegalArgumentException(
                    "Stage Snapshot is required for Command resolution");
        }
        if (invocation == null) {
            return null;
        }
        StageCommandReference selected = null;
        for (StageCommandReference reference
                : stageSnapshot.getCommandReferences()) {
            if (reference.getIdentifier().equals(
                    invocation.getIdentifier())) {
                selected = reference;
                break;
            }
        }
        if (selected == null) {
            throw new CommandResolutionException(
                    "WORKBENCH_STAGE_COMMAND_NOT_ALLOWED",
                    "Command is not selected by the frozen Stage Snapshot");
        }
        CommandDefinition command = artifactRegistry.requireCommand(
                selected.getIdentifier(), selected.getVersion(),
                selected.getContentHash());
        return command.resolve(
                selected.getContentHash(), invocation.getArguments());
    }

    public List<CommandDefinition> listCommands(
            WorkbenchStageSnapshot stageSnapshot) {
        if (stageSnapshot == null) {
            throw new IllegalArgumentException(
                    "Stage Snapshot is required for Command listing");
        }
        List<CommandDefinition> commands =
                new ArrayList<CommandDefinition>();
        for (StageCommandReference reference
                : stageSnapshot.getCommandReferences()) {
            commands.add(artifactRegistry.requireCommand(
                    reference.getIdentifier(), reference.getVersion(),
                    reference.getContentHash()));
        }
        return Collections.unmodifiableList(commands);
    }

    private ResolvedRuleBinding stageRule(
            WorkbenchStageSnapshot stageSnapshot) {
        return new ResolvedRuleBinding(
                "workbench/stage/"
                        + stageSnapshot.getDefinitionIdentifier(),
                String.valueOf(stageSnapshot.getDefinitionRevision()),
                STAGE_RULE_SOURCE,
                CanonicalHashing.sha256(stageSnapshot.getStageRules()),
                true, "Rules frozen from Workbench Stage Revision "
                + stageSnapshot.getDefinitionRevision());
    }

    private List<SkillPackage> resolveSkillPackages(
            WorkbenchStageSnapshot stageSnapshot, AgentType agentType) {
        Map<String, SkillPackage> selected =
                new HashMap<String, SkillPackage>();
        for (StageSkillReference reference
                : stageSnapshot.getSkillReferences()) {
            SkillPackage skill = artifactRegistry.requireSkill(
                    reference.getIdentifier(), reference.getVersion(),
                    reference.getPackageHash());
            SkillManifest manifest = skill.getManifest();
            if (!manifest.getCompatibleRuntimes().contains(
                    agentType.name())) {
                throw incompatible(
                        "Archived Skill is incompatible with Runtime: "
                                + reference.getIdentifier());
            }
            selected.put(reference.getIdentifier(), skill);
        }
        requireSkillGraphClosed(selected);
        return new ArrayList<SkillPackage>(selected.values());
    }

    private void requireSkillGraphClosed(
            Map<String, SkillPackage> selected) {
        for (SkillPackage skill : selected.values()) {
            SkillManifest manifest = skill.getManifest();
            for (SkillDependency dependency : manifest.getDependencies()) {
                SkillPackage resolved = selected.get(
                        dependency.getSkillId());
                if (resolved == null
                        || !dependency.getVersion().equals(
                        resolved.getManifest().getVersion())) {
                    throw incompatible(
                            "Archived Stage Skill dependency is not selected: "
                                    + dependency.getSkillId());
                }
            }
            for (String conflict : manifest.getConflicts()) {
                if (selected.containsKey(conflict)) {
                    throw incompatible(
                            "Archived Stage Skills conflict: "
                                    + manifest.getId() + " and " + conflict);
                }
            }
        }
    }

    private List<ResolvedSkillBinding> skillBindings(
            List<SkillPackage> skillPackages) {
        List<ResolvedSkillBinding> bindings =
                new ArrayList<ResolvedSkillBinding>();
        for (SkillPackage skill : skillPackages) {
            SkillManifest manifest = skill.getManifest();
            bindings.add(new ResolvedSkillBinding(
                    manifest.getId(), manifest.getVersion(),
                    ARTIFACT_SOURCE, skill.getPackageHash(),
                    manifest.getTrustSource().name()));
        }
        return bindings;
    }

    private List<ResolvedMcpServerBinding> resolveMcpServers(
            WorkbenchStageSnapshot stageSnapshot, RunMode runMode,
            AgentType agentType) {
        List<ResolvedMcpServerBinding> bindings =
                new ArrayList<ResolvedMcpServerBinding>();
        for (StageMcpServerReference reference
                : stageSnapshot.getMcpServerReferences()) {
            McpServerDefinition definition =
                    artifactRegistry.requireMcpServer(
                            reference.getIdentifier(),
                            reference.getVersion(),
                            reference.getDefinitionHash());
            if (!definition.getCompatibleRuntimes().contains(
                    agentType.name())
                    || definition.getMaximumAccess()
                    != reference.getMaximumAccess()
                    || !definition.getTransport().name().equals(
                    reference.getTransport())
                    || definition.hasUnsupportedResourceCapability()) {
                throw incompatible(
                        "Archived MCP Server is incompatible with Runtime: "
                                + reference.getIdentifier());
            }
            CapabilityAccess access = reference.getMaximumAccess();
            if (!runMode.modifiesWorkspace()
                    && access == CapabilityAccess.WRITE) {
                access = CapabilityAccess.READ;
            }
            bindings.add(new ResolvedMcpServerBinding(
                    definition.getId(), definition.getVersion(),
                    definition.getConfigurationHash(), access,
                    definition.getTransport().name()));
        }
        return bindings;
    }

    private void requireSupportedAgent(AgentType agentType) {
        if (agentType == AgentType.NATIVE) {
            throw incompatible(
                    "NATIVE diagnosis Runtime is unavailable to Workbench Stage");
        }
    }

    private WorkbenchDomainException incompatible(String message) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.RUN_MODE_FORBIDDEN, message);
    }
}
