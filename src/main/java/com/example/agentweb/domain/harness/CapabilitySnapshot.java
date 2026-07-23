package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 与 Stage Attempt 绑定的不可变能力与 Prompt 快照。
 *
 * <p>Snapshot Hash 刻画能力内容，不包含 Run/Attempt 身份和创建时间；因此同一输入与
 * 同一资源能得到稳定 Hash，同时数据库主键仍保证每个 Attempt 只能绑定一个快照。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class CapabilitySnapshot {

    public static final String SCHEMA_M2 = "M2";
    public static final String SCHEMA_M3 = "M3";
    public static final String SCHEMA_M3_1 = "M3.1";

    private final String runId;
    private final HarnessStage stage;
    private final int attemptNumber;
    private final AgentRuntime runtime;
    private final String environment;
    private final String policyVersion;
    private final String promptPackId;
    private final String promptPackVersion;
    private final String promptPackHash;
    private final Map<String, String> promptResourceHashes;
    private final List<SnapshotSkill> selectedSkills;
    private final List<RejectedSkill> rejectedSkills;
    private final List<CapabilityDecision> capabilityDecisions;
    private final String schemaVersion;
    private final List<SelectedMcpServer> selectedMcpServers;
    private final List<RejectedMcpServer> rejectedMcpServers;
    private final RuntimeEnforcementProfile runtimeEnforcementProfile;
    private final WorkspaceRuntimeInventory workspaceRuntimeInventory;
    private final List<HarnessPromptPart> promptParts;
    private final String finalPrompt;
    private final String promptHash;
    private final String snapshotHash;
    private final Instant createdAt;

    public static CapabilitySnapshot create(String runId, HarnessStage stage, int attemptNumber,
                                            AgentRuntime runtime, String environment, String policyVersion,
                                            PromptPack promptPack, SkillSelection selection,
                                            HarnessPromptAssembly assembly, Instant createdAt) {
        if (promptPack == null || selection == null || assembly == null) {
            throw new IllegalArgumentException("snapshot pack, selection and assembly must not be null");
        }
        List<SnapshotSkill> skills = new ArrayList<SnapshotSkill>();
        for (SelectedSkill selected : selection.getSelected()) {
            SkillPackage skillPackage = selected.getSkillPackage();
            skills.add(new SnapshotSkill(skillPackage.getManifest().getId(),
                    skillPackage.getManifest().getVersion(), skillPackage.getPackageHash(),
                    selected.getReason()));
        }
        CapabilitySnapshot unhashed = new CapabilitySnapshot(runId, stage, attemptNumber, runtime,
                environment, policyVersion, promptPack.getManifest().getId(),
                promptPack.getManifest().getVersion(), promptPack.getPackageHash(),
                promptPack.resourceHashes(), skills, selection.getRejected(),
                selection.getCapabilityDecisions(), SCHEMA_M2,
                Collections.<SelectedMcpServer>emptyList(),
                Collections.<RejectedMcpServer>emptyList(), RuntimeEnforcementProfile.legacy(),
                assembly.getParts(), assembly.getFinalPrompt(), assembly.getPromptHash(),
                temporaryHash(), createdAt, false);
        return new CapabilitySnapshot(runId, stage, attemptNumber, runtime, environment, policyVersion,
                promptPack.getManifest().getId(), promptPack.getManifest().getVersion(),
                promptPack.getPackageHash(), promptPack.resourceHashes(), skills,
                selection.getRejected(), selection.getCapabilityDecisions(), SCHEMA_M2,
                Collections.<SelectedMcpServer>emptyList(),
                Collections.<RejectedMcpServer>emptyList(), RuntimeEnforcementProfile.legacy(),
                assembly.getParts(), assembly.getFinalPrompt(), assembly.getPromptHash(),
                unhashed.computeHash(), createdAt, true);
    }

    public static CapabilitySnapshot create(String runId, HarnessStage stage, int attemptNumber,
                                            AgentRuntime runtime, String environment,
                                            String policyVersion, PromptPack promptPack,
                                            SkillSelection skillSelection, McpSelection mcpSelection,
                                            RuntimeEnforcementProfile enforcementProfile,
                                            WorkspaceRuntimeInventory workspaceRuntimeInventory,
                                            HarnessPromptAssembly assembly, Instant createdAt) {
        if (promptPack == null || skillSelection == null || mcpSelection == null
                || enforcementProfile == null || workspaceRuntimeInventory == null
                || assembly == null) {
            throw new IllegalArgumentException("M3.1 snapshot inputs must not be null");
        }
        List<SnapshotSkill> skills = snapshotSkills(skillSelection);
        CapabilitySnapshot unhashed = new CapabilitySnapshot(runId, stage, attemptNumber, runtime,
                environment, policyVersion, promptPack.getManifest().getId(),
                promptPack.getManifest().getVersion(), promptPack.getPackageHash(),
                promptPack.resourceHashes(), skills, skillSelection.getRejected(),
                skillSelection.getCapabilityDecisions(), SCHEMA_M3_1, mcpSelection.getSelected(),
                mcpSelection.getRejected(), enforcementProfile, workspaceRuntimeInventory,
                assembly.getParts(), assembly.getFinalPrompt(), assembly.getPromptHash(),
                temporaryHash(), createdAt, false);
        return new CapabilitySnapshot(runId, stage, attemptNumber, runtime, environment,
                policyVersion, promptPack.getManifest().getId(), promptPack.getManifest().getVersion(),
                promptPack.getPackageHash(), promptPack.resourceHashes(), skills,
                skillSelection.getRejected(), skillSelection.getCapabilityDecisions(), SCHEMA_M3_1,
                mcpSelection.getSelected(), mcpSelection.getRejected(), enforcementProfile,
                workspaceRuntimeInventory, assembly.getParts(), assembly.getFinalPrompt(),
                assembly.getPromptHash(), unhashed.computeHash(), createdAt, true);
    }

    public static CapabilitySnapshot create(String runId, HarnessStage stage, int attemptNumber,
                                            AgentRuntime runtime, String environment,
                                            String policyVersion, PromptPack promptPack,
                                            SkillSelection skillSelection, McpSelection mcpSelection,
                                            RuntimeEnforcementProfile enforcementProfile,
                                            HarnessPromptAssembly assembly, Instant createdAt) {
        if (promptPack == null || skillSelection == null || mcpSelection == null
                || enforcementProfile == null || assembly == null) {
            throw new IllegalArgumentException("M3 snapshot inputs must not be null");
        }
        List<SnapshotSkill> skills = snapshotSkills(skillSelection);
        CapabilitySnapshot unhashed = new CapabilitySnapshot(runId, stage, attemptNumber, runtime,
                environment, policyVersion, promptPack.getManifest().getId(),
                promptPack.getManifest().getVersion(), promptPack.getPackageHash(),
                promptPack.resourceHashes(), skills, skillSelection.getRejected(),
                skillSelection.getCapabilityDecisions(), SCHEMA_M3, mcpSelection.getSelected(),
                mcpSelection.getRejected(), enforcementProfile, assembly.getParts(),
                assembly.getFinalPrompt(), assembly.getPromptHash(), temporaryHash(), createdAt, false);
        return new CapabilitySnapshot(runId, stage, attemptNumber, runtime, environment, policyVersion,
                promptPack.getManifest().getId(), promptPack.getManifest().getVersion(),
                promptPack.getPackageHash(), promptPack.resourceHashes(), skills,
                skillSelection.getRejected(), skillSelection.getCapabilityDecisions(), SCHEMA_M3,
                mcpSelection.getSelected(), mcpSelection.getRejected(), enforcementProfile,
                assembly.getParts(), assembly.getFinalPrompt(), assembly.getPromptHash(),
                unhashed.computeHash(), createdAt, true);
    }

    public static CapabilitySnapshot restore(String runId, HarnessStage stage, int attemptNumber,
                                             AgentRuntime runtime, String environment, String policyVersion,
                                             String promptPackId, String promptPackVersion,
                                             String promptPackHash, Map<String, String> promptResourceHashes,
                                             List<SnapshotSkill> selectedSkills,
                                             List<RejectedSkill> rejectedSkills,
                                             List<CapabilityDecision> capabilityDecisions,
                                             List<HarnessPromptPart> promptParts, String finalPrompt,
                                             String promptHash, String snapshotHash, Instant createdAt) {
        return new CapabilitySnapshot(runId, stage, attemptNumber, runtime, environment, policyVersion,
                promptPackId, promptPackVersion, promptPackHash, promptResourceHashes, selectedSkills,
                rejectedSkills, capabilityDecisions, SCHEMA_M2,
                Collections.<SelectedMcpServer>emptyList(),
                Collections.<RejectedMcpServer>emptyList(), RuntimeEnforcementProfile.legacy(),
                promptParts, finalPrompt, promptHash, snapshotHash, createdAt, true);
    }

    public static CapabilitySnapshot restoreM3(String runId, HarnessStage stage, int attemptNumber,
                                               AgentRuntime runtime, String environment,
                                               String policyVersion, String promptPackId,
                                               String promptPackVersion, String promptPackHash,
                                               Map<String, String> promptResourceHashes,
                                               List<SnapshotSkill> selectedSkills,
                                               List<RejectedSkill> rejectedSkills,
                                               List<CapabilityDecision> capabilityDecisions,
                                               List<SelectedMcpServer> selectedMcpServers,
                                               List<RejectedMcpServer> rejectedMcpServers,
                                               RuntimeEnforcementProfile enforcementProfile,
                                               List<HarnessPromptPart> promptParts,
                                               String finalPrompt, String promptHash,
                                               String snapshotHash, Instant createdAt) {
        return new CapabilitySnapshot(runId, stage, attemptNumber, runtime, environment,
                policyVersion, promptPackId, promptPackVersion, promptPackHash,
                promptResourceHashes, selectedSkills, rejectedSkills, capabilityDecisions,
                SCHEMA_M3, selectedMcpServers, rejectedMcpServers, enforcementProfile,
                promptParts, finalPrompt, promptHash, snapshotHash, createdAt, true);
    }

    public static CapabilitySnapshot restoreM31(String runId, HarnessStage stage, int attemptNumber,
                                                AgentRuntime runtime, String environment,
                                                String policyVersion, String promptPackId,
                                                String promptPackVersion, String promptPackHash,
                                                Map<String, String> promptResourceHashes,
                                                List<SnapshotSkill> selectedSkills,
                                                List<RejectedSkill> rejectedSkills,
                                                List<CapabilityDecision> capabilityDecisions,
                                                List<SelectedMcpServer> selectedMcpServers,
                                                List<RejectedMcpServer> rejectedMcpServers,
                                                RuntimeEnforcementProfile enforcementProfile,
                                                WorkspaceRuntimeInventory workspaceRuntimeInventory,
                                                List<HarnessPromptPart> promptParts,
                                                String finalPrompt, String promptHash,
                                                String snapshotHash, Instant createdAt) {
        return new CapabilitySnapshot(runId, stage, attemptNumber, runtime, environment,
                policyVersion, promptPackId, promptPackVersion, promptPackHash,
                promptResourceHashes, selectedSkills, rejectedSkills, capabilityDecisions,
                SCHEMA_M3_1, selectedMcpServers, rejectedMcpServers, enforcementProfile,
                workspaceRuntimeInventory, promptParts, finalPrompt, promptHash, snapshotHash,
                createdAt, true);
    }

    private CapabilitySnapshot(String runId, HarnessStage stage, int attemptNumber,
                               AgentRuntime runtime, String environment, String policyVersion,
                               String promptPackId, String promptPackVersion, String promptPackHash,
                               Map<String, String> promptResourceHashes,
                               List<SnapshotSkill> selectedSkills, List<RejectedSkill> rejectedSkills,
                               List<CapabilityDecision> capabilityDecisions,
                               String schemaVersion, List<SelectedMcpServer> selectedMcpServers,
                               List<RejectedMcpServer> rejectedMcpServers,
                               RuntimeEnforcementProfile runtimeEnforcementProfile,
                               List<HarnessPromptPart> promptParts, String finalPrompt,
                               String promptHash, String snapshotHash, Instant createdAt,
                               boolean validateHash) {
        this(runId, stage, attemptNumber, runtime, environment, policyVersion, promptPackId,
                promptPackVersion, promptPackHash, promptResourceHashes, selectedSkills,
                rejectedSkills, capabilityDecisions, schemaVersion, selectedMcpServers,
                rejectedMcpServers, runtimeEnforcementProfile, WorkspaceRuntimeInventory.empty(),
                promptParts, finalPrompt, promptHash, snapshotHash, createdAt, validateHash);
    }

    private CapabilitySnapshot(String runId, HarnessStage stage, int attemptNumber,
                               AgentRuntime runtime, String environment, String policyVersion,
                               String promptPackId, String promptPackVersion, String promptPackHash,
                               Map<String, String> promptResourceHashes,
                               List<SnapshotSkill> selectedSkills, List<RejectedSkill> rejectedSkills,
                               List<CapabilityDecision> capabilityDecisions,
                               String schemaVersion, List<SelectedMcpServer> selectedMcpServers,
                               List<RejectedMcpServer> rejectedMcpServers,
                               RuntimeEnforcementProfile runtimeEnforcementProfile,
                               WorkspaceRuntimeInventory workspaceRuntimeInventory,
                               List<HarnessPromptPart> promptParts, String finalPrompt,
                               String promptHash, String snapshotHash, Instant createdAt,
                               boolean validateHash) {
        this.runId = DomainText.require(runId, "snapshot run id", 128);
        if (stage == null || runtime == null || attemptNumber < 1) {
            throw new IllegalArgumentException("snapshot stage/runtime and positive attempt are required");
        }
        this.stage = stage;
        this.attemptNumber = attemptNumber;
        this.runtime = runtime;
        this.environment = DomainText.require(environment, "snapshot environment", 120);
        this.policyVersion = DomainText.require(policyVersion, "snapshot policy version", 60);
        this.promptPackId = DomainText.require(promptPackId, "snapshot prompt pack id", 120);
        this.promptPackVersion = DomainText.require(promptPackVersion, "snapshot prompt pack version", 60);
        this.promptPackHash = DomainText.requireSha256(promptPackHash, "snapshot prompt pack hash");
        this.promptResourceHashes = immutableHashes(promptResourceHashes);
        this.selectedSkills = immutable(selectedSkills, "snapshot selected skills");
        this.rejectedSkills = immutable(rejectedSkills, "snapshot rejected skills");
        this.capabilityDecisions = immutable(capabilityDecisions, "snapshot capability decisions");
        if (!SCHEMA_M2.equals(schemaVersion) && !SCHEMA_M3.equals(schemaVersion)
                && !SCHEMA_M3_1.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported snapshot schema version: " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        this.selectedMcpServers = immutable(selectedMcpServers, "snapshot selected MCP servers");
        this.rejectedMcpServers = immutable(rejectedMcpServers, "snapshot rejected MCP servers");
        if (runtimeEnforcementProfile == null) {
            throw new IllegalArgumentException("runtime enforcement profile must not be null");
        }
        this.runtimeEnforcementProfile = runtimeEnforcementProfile;
        if (workspaceRuntimeInventory == null) {
            throw new IllegalArgumentException("workspace runtime inventory must not be null");
        }
        this.workspaceRuntimeInventory = workspaceRuntimeInventory;
        this.promptParts = immutable(promptParts, "snapshot prompt parts");
        if (finalPrompt == null || finalPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("snapshot final prompt must not be blank");
        }
        this.finalPrompt = finalPrompt;
        this.promptHash = DomainText.requireSha256(promptHash, "snapshot prompt hash");
        if (!HarnessHashing.sha256(finalPrompt).equals(this.promptHash)) {
            throw new IllegalArgumentException("snapshot prompt hash does not match prompt");
        }
        this.snapshotHash = DomainText.requireSha256(snapshotHash, "snapshot hash");
        this.createdAt = DomainText.requireTime(createdAt, "snapshot created time");
        if (validateHash && !computeHash().equals(this.snapshotHash)) {
            throw new IllegalArgumentException("snapshot hash does not match capability content");
        }
    }

    private String computeHash() {
        StringBuilder canonical = new StringBuilder();
        HarnessHashing.appendFramed(canonical, "stage", stage);
        HarnessHashing.appendFramed(canonical, "runtime", runtime);
        HarnessHashing.appendFramed(canonical, "environment", environment);
        HarnessHashing.appendFramed(canonical, "policyVersion", policyVersion);
        HarnessHashing.appendFramed(canonical, "promptPackId", promptPackId);
        HarnessHashing.appendFramed(canonical, "promptPackVersion", promptPackVersion);
        HarnessHashing.appendFramed(canonical, "promptPackHash", promptPackHash);
        for (Map.Entry<String, String> entry : new TreeMap<String, String>(promptResourceHashes).entrySet()) {
            HarnessHashing.appendFramed(canonical, "promptResourcePath", entry.getKey());
            HarnessHashing.appendFramed(canonical, "promptResourceHash", entry.getValue());
        }
        for (SnapshotSkill skill : selectedSkills) {
            HarnessHashing.appendFramed(canonical, "skillId", skill.getId());
            HarnessHashing.appendFramed(canonical, "skillVersion", skill.getVersion());
            HarnessHashing.appendFramed(canonical, "skillPackageHash", skill.getPackageHash());
            HarnessHashing.appendFramed(canonical, "skillReason", skill.getReason());
        }
        for (RejectedSkill skill : rejectedSkills) {
            HarnessHashing.appendFramed(canonical, "rejectedSkillId", skill.getSkillId());
            HarnessHashing.appendFramed(canonical, "rejectedSkillVersion", skill.getVersion());
            HarnessHashing.appendFramed(canonical, "rejectedSkillReason", skill.getReason());
        }
        for (CapabilityDecision decision : capabilityDecisions) {
            HarnessHashing.appendFramed(canonical, "capabilitySkill", decision.getSkillId());
            HarnessHashing.appendFramed(canonical, "capabilityKind", decision.getRequest().getKind());
            HarnessHashing.appendFramed(canonical, "capabilityAccess", decision.getRequest().getAccess());
            HarnessHashing.appendFramed(canonical, "capabilityResource", decision.getRequest().getResource());
            HarnessHashing.appendFramed(canonical, "capabilityAuthorized", decision.isAuthorized());
            HarnessHashing.appendFramed(canonical, "capabilityReason", decision.getReason());
        }
        if (SCHEMA_M3.equals(schemaVersion) || SCHEMA_M3_1.equals(schemaVersion)) {
            HarnessHashing.appendFramed(canonical, "schemaVersion", schemaVersion);
            for (SelectedMcpServer server : selectedMcpServers) {
                HarnessHashing.appendFramed(canonical, "mcpId", server.getId());
                HarnessHashing.appendFramed(canonical, "mcpVersion", server.getVersion());
                HarnessHashing.appendFramed(canonical, "mcpConfigurationHash",
                        server.getConfigurationHash());
                for (McpCapability capability : server.getCapabilities()) {
                    HarnessHashing.appendFramed(canonical, "mcpCapabilityId", capability.getId());
                    HarnessHashing.appendFramed(canonical, "mcpCapabilityType", capability.getType());
                    HarnessHashing.appendFramed(canonical, "mcpCapabilityAccess", capability.getAccess());
                }
                for (McpSecretReference secretReference : server.getSecretReferences()) {
                    HarnessHashing.appendFramed(canonical, "mcpSecretEnvironment",
                            secretReference.getEnvironmentVariable());
                    HarnessHashing.appendFramed(canonical, "mcpSecretReference",
                            secretReference.getReference());
                }
            }
            for (RejectedMcpServer server : rejectedMcpServers) {
                HarnessHashing.appendFramed(canonical, "rejectedMcpId", server.getId());
                HarnessHashing.appendFramed(canonical, "rejectedMcpVersion", server.getVersion());
                HarnessHashing.appendFramed(canonical, "rejectedMcpReason", server.getReason());
            }
            HarnessHashing.appendFramed(canonical, "runtimeEnforcementHash",
                    runtimeEnforcementProfile.getProfileHash());
        }
        if (SCHEMA_M3_1.equals(schemaVersion)) {
            for (SelectedMcpServer server : selectedMcpServers) {
                HarnessHashing.appendFramed(canonical, "mcpRequired", server.isRequired());
                for (String toolName : server.getEnabledToolNames()) {
                    HarnessHashing.appendFramed(canonical, "mcpEnabledTool", toolName);
                }
                for (String toolName : server.getDisabledToolNames()) {
                    HarnessHashing.appendFramed(canonical, "mcpDisabledTool", toolName);
                }
                HarnessHashing.appendFramed(canonical, "mcpStartupTimeoutSeconds",
                        server.getStartupTimeoutSeconds());
                HarnessHashing.appendFramed(canonical, "mcpToolTimeoutSeconds",
                        server.getToolTimeoutSeconds());
            }
            HarnessHashing.appendFramed(canonical, "workspaceRuntimeInventoryHash",
                    workspaceRuntimeInventory.getInventoryHash());
        }
        HarnessHashing.appendFramed(canonical, "promptHash", promptHash);
        return HarnessHashing.sha256(canonical.toString());
    }

    private Map<String, String> immutableHashes(Map<String, String> hashes) {
        if (hashes == null) {
            throw new IllegalArgumentException("snapshot resource hashes must not be null");
        }
        Map<String, String> copy = new TreeMap<String, String>();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            copy.put(DomainText.require(entry.getKey(), "snapshot resource path", 500),
                    DomainText.requireSha256(entry.getValue(), "snapshot resource hash"));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(copy));
    }

    private <T> List<T> immutable(List<T> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not be null or contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    private static String temporaryHash() {
        return HarnessHashing.sha256("temporary");
    }

    private static List<SnapshotSkill> snapshotSkills(SkillSelection selection) {
        List<SnapshotSkill> skills = new ArrayList<SnapshotSkill>();
        for (SelectedSkill selected : selection.getSelected()) {
            SkillPackage skillPackage = selected.getSkillPackage();
            skills.add(new SnapshotSkill(skillPackage.getManifest().getId(),
                    skillPackage.getManifest().getVersion(), skillPackage.getPackageHash(),
                    selected.getReason()));
        }
        return skills;
    }
}
