package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityArtifactRegistry;
import com.example.agentweb.domain.capability.CapabilityRequest;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.CommandResolutionException;
import com.example.agentweb.domain.capability.McpCapability;
import com.example.agentweb.domain.capability.McpCapabilityType;
import com.example.agentweb.domain.capability.McpSecretReference;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.ResolvedCommandBinding;
import com.example.agentweb.domain.capability.SkillDependency;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.ResolvedCapabilityResolution;
import com.example.agentweb.domain.workbench.RunMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态 Stage 从不可变 Artifact Registry 解析 Run 能力和 Command Allowlist。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageCapabilityResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-05T11:00:00Z");
    private static final String RUNTIME_COMPATIBILITY = "m0-2026-07-22";

    @Test
    void should_ResolveExactArchivedStageRulesSkillsAndMcp() {
        // Given
        InMemoryArtifactRegistry registry = artifacts();
        WorkbenchStageSnapshot snapshot = stageSnapshot(registry);
        WorkbenchStageCapabilityResolver resolver =
                new WorkbenchStageCapabilityResolver(registry);

        // When
        ResolvedCapabilityResolution resolution = resolver.resolve(
                snapshot, RunMode.DISCUSS_READ_ONLY, AgentType.CODEX,
                RUNTIME_COMPATIBILITY);

        // Then
        assertEquals(snapshot.getSnapshotHash(),
                resolution.getBinding().getProfileHash());
        assertEquals(1, resolution.getRuleContents().size());
        assertEquals("Follow frozen Stage rules.",
                resolution.getRuleContents().get(0).getContent());
        assertEquals(1, resolution.getBinding().getSkills().size());
        assertEquals("domain-modeling-audit",
                resolution.getBinding().getSkills().get(0).getId());
        assertEquals(1, resolution.getBinding().getMcpServers().size());
        assertEquals(CapabilityAccess.READ,
                resolution.getBinding().getMcpServers().get(0).getAccess());
        assertEquals(RUNTIME_COMPATIBILITY,
                resolution.getBinding().getRuntimeCompatibility());
        assertTrue(resolution.getBinding().getRejected().isEmpty());
    }

    @Test
    void should_ResolveOnlyCommandSelectedByFrozenStage() {
        // Given
        InMemoryArtifactRegistry registry = artifacts();
        WorkbenchStageCapabilityResolver resolver =
                new WorkbenchStageCapabilityResolver(registry);
        WorkbenchStageSnapshot snapshot = stageSnapshot(registry);

        // When
        ResolvedCommandBinding binding = resolver.resolveCommand(
                snapshot, WorkbenchStageCommandInvocation.parse(
                        "/architecture-review module A"));

        // Then
        assertEquals("architecture-review", binding.getIdentifier());
        assertEquals("Review module A", binding.getExpandedPrompt());

        CommandResolutionException failure = assertThrows(
                CommandResolutionException.class,
                () -> resolver.resolveCommand(
                        snapshot, WorkbenchStageCommandInvocation.parse(
                                "/unselected-command module A")));
        assertEquals("WORKBENCH_STAGE_COMMAND_NOT_ALLOWED",
                failure.getCode());
        assertEquals(1, registry.commandReads);
    }

    @Test
    void should_ResolveSkillInvocationFromFrozenStage() {
        // Given
        InMemoryArtifactRegistry registry = artifacts();
        WorkbenchStageCapabilityResolver resolver =
                new WorkbenchStageCapabilityResolver(registry);
        WorkbenchStageSnapshot snapshot = stageSnapshot(registry);

        // When
        ResolvedCommandBinding binding = resolver.resolveCommand(
                snapshot, WorkbenchStageCommandInvocation.parse(
                        "/domain-modeling-audit"));

        // Then
        assertEquals("domain-modeling-audit", binding.getIdentifier());
        assertEquals("1.0.0", binding.getVersion());
        assertEquals(registry.skill.getPackageHash(),
                binding.getContentHash());
        assertEquals("# Domain Modeling Audit",
                binding.getExpandedPrompt());
        assertFalse(binding.getExpandedPrompt().contains("$ARGUMENTS"));
    }

    @Test
    void should_ListStageSkillsAlongsideCommands() {
        // Given
        InMemoryArtifactRegistry registry = artifacts();
        WorkbenchStageCapabilityResolver resolver =
                new WorkbenchStageCapabilityResolver(registry);
        WorkbenchStageSnapshot snapshot = stageSnapshot(registry);

        // When
        java.util.List<SkillPackage> skills =
                resolver.listSkills(snapshot);

        // Then
        assertEquals(1, skills.size());
        assertEquals("domain-modeling-audit",
                skills.get(0).getManifest().getId());
    }

    @Test
    void should_RejectRuntimeIncompatibleArchivedCapability() {
        // Given
        InMemoryArtifactRegistry registry = artifacts();
        registry.skill = skill(set("CLAUDE"));
        WorkbenchStageSnapshot snapshot = stageSnapshot(registry);
        WorkbenchStageCapabilityResolver resolver =
                new WorkbenchStageCapabilityResolver(registry);

        // When / Then
        assertThrows(IllegalStateException.class,
                () -> resolver.resolve(
                        snapshot, RunMode.DISCUSS_READ_ONLY,
                        AgentType.CODEX, RUNTIME_COMPATIBILITY));
    }

    private WorkbenchStageSnapshot stageSnapshot(
            InMemoryArtifactRegistry registry) {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft(
                "solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "阶段说明",
                        "Follow frozen Stage rules.",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.singletonList(new StageCommandSelection(
                                registry.command.getIdentifier(),
                                registry.command.getVersion())),
                        Collections.singletonList(new StageSkillSelection(
                                registry.skill.getManifest().getId(),
                                registry.skill.getManifest().getVersion(), true)),
                        Collections.singletonList(new StageMcpServerSelection(
                                registry.mcpServer.getId(),
                                registry.mcpServer.getVersion(), true))),
                StageCatalogEditor.create("admin-1", "Admin"), NOW);
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "solution-design", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.singletonList(new StageCommandReference(
                                        registry.command.getIdentifier(),
                                        registry.command.getVersion(),
                                        registry.command.getContentHash())),
                                Collections.singletonList(new StageSkillReference(
                                        registry.skill.getManifest().getId(),
                                        registry.skill.getManifest().getVersion(),
                                        registry.skill.getPackageHash(), true)),
                                Collections.singletonList(new StageMcpServerReference(
                                        registry.mcpServer.getId(),
                                        registry.mcpServer.getVersion(),
                                        registry.mcpServer.getConfigurationHash(), true,
                                        registry.mcpServer.getMaximumAccess(),
                                        registry.mcpServer.getTransport().name()))),
                        StageCatalogEditor.create("admin-1", "Admin"),
                        NOW.plusSeconds(1)));
    }

    private InMemoryArtifactRegistry artifacts() {
        CommandDefinition command = CommandDefinition.create(
                "architecture-review", "1.0.0", "Architecture Review",
                "Review architecture", "<module>", "Review $ARGUMENTS",
                "platform-commands", NOW);
        SkillPackage skill = skill(set("CODEX"));
        McpServerDefinition mcpServer = new McpServerDefinition(
                "repository-query", "1.0.0", "Repository Query",
                set("SOLUTION_DESIGN"), set("CODEX"),
                Collections.singletonList("repository-query"),
                Collections.singletonList(new McpCapability(
                        "query", McpCapabilityType.TOOL,
                        CapabilityAccess.READ)),
                Collections.<McpSecretReference>emptyList(), 10, 30,
                CanonicalHashing.sha256("repository-query@1"));
        return new InMemoryArtifactRegistry(command, skill, mcpServer);
    }

    private SkillPackage skill(Set<String> runtimes) {
        SkillManifest manifest = new SkillManifest(
                "domain-modeling-audit", "1.0.0",
                "Audit domain boundaries", set("SOLUTION_DESIGN"),
                Collections.emptySet(), Collections.emptySet(),
                "SKILL.md", Collections.emptySet(),
                Collections.<SkillDependency>emptyList(),
                Collections.emptySet(), runtimes,
                SkillTrustSource.PLATFORM,
                Collections.<CapabilityRequest>emptyList());
        return new SkillPackage(
                manifest, CanonicalHashing.sha256("domain-modeling-audit@1"),
                "# Domain Modeling Audit", Collections.emptyMap());
    }

    private Set<String> set(String value) {
        return new LinkedHashSet<String>(Collections.singleton(value));
    }

    private static final class InMemoryArtifactRegistry
            implements CapabilityArtifactRegistry {

        private final CommandDefinition command;
        private SkillPackage skill;
        private final McpServerDefinition mcpServer;
        private int commandReads;

        private InMemoryArtifactRegistry(
                CommandDefinition command, SkillPackage skill,
                McpServerDefinition mcpServer) {
            this.command = command;
            this.skill = skill;
            this.mcpServer = mcpServer;
        }

        @Override
        public void archiveCommand(CommandDefinition definition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void archiveSkill(SkillPackage skillPackage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void archiveMcpServer(McpServerDefinition definition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandDefinition requireCommand(
                String identifier, String version,
                String expectedContentHash) {
            commandReads++;
            if (!command.getIdentifier().equals(identifier)
                    || !command.getVersion().equals(version)
                    || !command.getContentHash().equals(expectedContentHash)) {
                throw new IllegalStateException("Command Artifact mismatch");
            }
            return command;
        }

        @Override
        public SkillPackage requireSkill(
                String identifier, String version,
                String expectedPackageHash) {
            if (!skill.getManifest().getId().equals(identifier)
                    || !skill.getManifest().getVersion().equals(version)
                    || !skill.getPackageHash().equals(expectedPackageHash)) {
                throw new IllegalStateException("Skill Artifact mismatch");
            }
            return skill;
        }

        @Override
        public McpServerDefinition requireMcpServer(
                String identifier, String version,
                String expectedDefinitionHash) {
            if (!mcpServer.getId().equals(identifier)
                    || !mcpServer.getVersion().equals(version)
                    || !mcpServer.getConfigurationHash().equals(
                    expectedDefinitionHash)) {
                throw new IllegalStateException("MCP Artifact mismatch");
            }
            return mcpServer;
        }
    }
}
