package com.example.agentweb.domain.mode;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityArtifactRegistry;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.capability.SkillDependency;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.shared.AgentType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从会话冻结的模式快照和不可变 Artifact Registry 解析单次 Run 能力。
 *
 * <p>镜像 Workbench Stage 的信任链：引用按 {@code (identifier, version, hash)}
 * exact 重验，Skill 依赖图闭包校验，MCP 定义/transport/访问级别必须与快照一致；
 * 任一失配即 fail-closed（模式快照引用的能力已被归档或内容漂移）。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
public final class ChatModeCapabilityResolver {

    private static final String CAPABILITY_POLICY_VERSION = "chat-mode-policy@1";
    private static final String ARTIFACT_SOURCE = "CHAT_MODE_SNAPSHOT";

    private final CapabilityArtifactRegistry artifactRegistry;

    public ChatModeCapabilityResolver(CapabilityArtifactRegistry artifactRegistry) {
        this.artifactRegistry = Objects.requireNonNull(
                artifactRegistry, "Capability Artifact Registry");
    }

    public ResolvedModeCapabilities resolve(
            ModeSnapshot snapshot, AgentType agentType, String runtimeCompatibility) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(agentType, "agentType");
        Objects.requireNonNull(runtimeCompatibility, "runtimeCompatibility");
        String runtime = agentType.name();
        List<CommandDefinition> commands = resolveCommands(snapshot);
        List<SkillPackage> skillPackages = resolveSkills(snapshot, runtime);
        List<ResolvedSkillBinding> skills = skillBindings(skillPackages);
        List<ResolvedMcpServerBinding> mcpServers = resolveMcpServers(snapshot, runtime);
        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                CAPABILITY_POLICY_VERSION,
                "chat-mode/" + snapshot.getIdentifier(),
                "1",
                snapshot.snapshotHash(),
                Collections.emptyList(), skills, mcpServers,
                Collections.emptyList(), runtimeCompatibility);
        return new ResolvedModeCapabilities(binding, commands);
    }

    private List<CommandDefinition> resolveCommands(ModeSnapshot snapshot) {
        List<CommandDefinition> commands = new ArrayList<CommandDefinition>();
        for (ModeCapabilities.CommandRef ref : snapshot.getCommands()) {
            // CommandDefinition 是纯 prompt 模板（无 runtime 兼容维度），registry 按 hash 精确重验即可
            commands.add(artifactRegistry.requireCommand(
                    ref.getIdentifier(), ref.getVersion(), ref.getContentHash()));
        }
        return commands;
    }

    private List<SkillPackage> resolveSkills(ModeSnapshot snapshot, String runtime) {
        Map<String, SkillPackage> selected = new HashMap<String, SkillPackage>();
        for (ModeCapabilities.SkillRef ref : snapshot.getSkills()) {
            SkillPackage skill = artifactRegistry.requireSkill(
                    ref.getIdentifier(), ref.getVersion(), ref.getContentHash());
            SkillManifest manifest = skill.getManifest();
            if (!manifest.getCompatibleRuntimes().contains(runtime)) {
                throw mismatch("Mode skill is incompatible with Runtime: "
                        + ref.getIdentifier());
            }
            selected.put(ref.getIdentifier(), skill);
        }
        requireSkillGraphClosed(selected);
        return new ArrayList<SkillPackage>(selected.values());
    }

    private void requireSkillGraphClosed(Map<String, SkillPackage> selected) {
        for (SkillPackage skill : selected.values()) {
            SkillManifest manifest = skill.getManifest();
            for (SkillDependency dependency : manifest.getDependencies()) {
                SkillPackage resolved = selected.get(dependency.getSkillId());
                if (resolved == null
                        || !dependency.getVersion().equals(resolved.getManifest().getVersion())) {
                    throw mismatch("Mode skill dependency is not selected: "
                            + dependency.getSkillId());
                }
            }
            for (String conflict : manifest.getConflicts()) {
                if (selected.containsKey(conflict)) {
                    throw mismatch("Mode skills conflict: "
                            + manifest.getId() + " and " + conflict);
                }
            }
        }
    }

    private List<ResolvedSkillBinding> skillBindings(List<SkillPackage> packages) {
        List<ResolvedSkillBinding> bindings = new ArrayList<ResolvedSkillBinding>();
        for (SkillPackage skill : packages) {
            SkillManifest manifest = skill.getManifest();
            bindings.add(new ResolvedSkillBinding(
                    manifest.getId(), manifest.getVersion(),
                    ARTIFACT_SOURCE, skill.getPackageHash(),
                    manifest.getTrustSource().name()));
        }
        return bindings;
    }

    private List<ResolvedMcpServerBinding> resolveMcpServers(ModeSnapshot snapshot, String runtime) {
        List<ResolvedMcpServerBinding> bindings = new ArrayList<ResolvedMcpServerBinding>();
        for (ModeCapabilities.McpServerRef ref : snapshot.getMcpServers()) {
            McpServerDefinition definition = artifactRegistry.requireMcpServer(
                    ref.getIdentifier(), ref.getVersion(), ref.getContentHash());
            if (!definition.getCompatibleRuntimes().contains(runtime)
                    || definition.getMaximumAccess() != CapabilityAccess.valueOf(ref.getMaximumAccess())
                    || !definition.getTransport().name().equals(ref.getTransport())
                    || definition.hasUnsupportedResourceCapability()) {
                throw mismatch("Mode MCP Server no longer matches its snapshot: "
                        + ref.getIdentifier());
            }
            bindings.add(new ResolvedMcpServerBinding(
                    definition.getId(), definition.getVersion(),
                    definition.getConfigurationHash(),
                    CapabilityAccess.valueOf(ref.getMaximumAccess()),
                    definition.getTransport().name()));
        }
        return bindings;
    }

    private static ModeCapabilityResolutionException mismatch(String message) {
        return new ModeCapabilityResolutionException(message);
    }
}
