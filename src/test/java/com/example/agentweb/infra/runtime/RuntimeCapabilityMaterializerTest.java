package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.ExecutionIdentity;
import com.example.agentweb.app.runtime.port.HistoryDelivery;
import com.example.agentweb.app.runtime.port.PromptPayload;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.McpCapability;
import com.example.agentweb.domain.capability.McpCapabilityType;
import com.example.agentweb.domain.capability.McpSecretReference;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 启动前 Skill/MCP exact Catalog 物化与 Secret 生命周期测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeCapabilityMaterializerTest {

    @TempDir
    Path tempDir;

    @Test
    void materializesExactSkillPackageAndMcpAsSingleRunCodexOverrides()
            throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary"));
        SkillPackage skill = skillPackage("java-tdd", "skill-package-v1");
        McpServerDefinition mcp = mcpDefinition("repository-query", "mcp-v1");
        AgentExecutionPlan plan = plan(primary, binding(skill, mcp));
        RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace =
                new RuntimeWorkspaceMaterializer(tempDir.resolve("runtime"))
                        .materialize(plan);
        RuntimeCapabilityMaterializer materializer =
                new RuntimeCapabilityMaterializer(
                        () -> Collections.singletonList(skill),
                        () -> Collections.singletonList(mcp),
                        reference -> {
                            assertEquals("REPOSITORY_QUERY_TOKEN", reference);
                            return "top-secret-value".toCharArray();
                        });

        RuntimeCapabilityMaterialization actual =
                materializer.materialize(plan, workspace);

        Path entry = actual.getSkills().get(0).getEntryPath();
        assertTrue(entry.startsWith(workspace.getExecutionRoot()));
        assertEquals("# java-tdd exact", new String(
                Files.readAllBytes(entry), StandardCharsets.UTF_8));
        assertEquals("exact rules", new String(Files.readAllBytes(
                entry.getParent().resolve("references/rules.md")),
                StandardCharsets.UTF_8));
        RuntimeCapabilityMaterialization.MaterializedMcpServer selected =
                actual.getMcpServers().get(0);
        assertEquals(Collections.singletonList("read_repository"),
                selected.getEnabledToolNames());
        assertEquals(Collections.singletonList("write_repository"),
                selected.getDisabledToolNames());

        Map<String, String> environment = new HashMap<String, String>();
        actual.applySecretEnvironment(environment);
        assertEquals("top-secret-value", environment.get("REPOSITORY_QUERY_API_KEY"));
        List<String> command = new RuntimeCommandFactory("codex")
                .create(plan, workspace, actual);
        assertOverride(command, "skills.config=[{path=\""
                + tomlEscape(entry.toString()) + "\",enabled=true}]");
        assertOverride(command,
                "mcp_servers.repository-query.command=\"repository-mcp\"");
        assertOverride(command,
                "mcp_servers.repository-query.args=[\"--stdio\"]");
        assertOverride(command,
                "mcp_servers.repository-query.env_vars=[\"REPOSITORY_QUERY_API_KEY\"]");
        assertOverride(command, "mcp_servers.repository-query.required=true");
        assertOverride(command,
                "mcp_servers.repository-query.startup_timeout_sec=10");
        assertOverride(command,
                "mcp_servers.repository-query.tool_timeout_sec=30");
        assertOverride(command,
                "mcp_servers.repository-query.enabled_tools=[\"read_repository\"]");
        assertOverride(command,
                "mcp_servers.repository-query.disabled_tools=[\"write_repository\"]");
        assertFalse(command.toString().contains("top-secret-value"));
        assertFalse(actual.toString().contains("top-secret-value"));

        actual.clearSecretEnvironment(environment);
        actual.close();

        assertFalse(environment.containsKey("REPOSITORY_QUERY_API_KEY"));
        assertThrows(IllegalStateException.class,
                () -> actual.applySecretEnvironment(environment));
    }

    @Test
    void rejectsChangedSkillPackageHashAndRemovesPartialMaterialization()
            throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-changed-skill"));
        SkillPackage snapshotted = skillPackage("java-tdd", "skill-package-v1");
        SkillPackage changed = skillPackage("java-tdd", "skill-package-v2");
        AgentExecutionPlan plan = plan(primary, binding(
                snapshotted, null));
        RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace =
                new RuntimeWorkspaceMaterializer(tempDir.resolve("runtime-changed-skill"))
                        .materialize(plan);
        RuntimeCapabilityMaterializer materializer =
                new RuntimeCapabilityMaterializer(
                        () -> Collections.singletonList(changed),
                        Collections::emptyList,
                        reference -> new char[0]);

        assertThrows(IllegalStateException.class,
                () -> materializer.materialize(plan, workspace));

        assertFalse(Files.exists(workspace.getExecutionRoot().resolve("capabilities")));
    }

    @Test
    void rejectsChangedMcpDefinitionBeforeResolvingAnySecret() throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-changed-mcp"));
        McpServerDefinition snapshotted = mcpDefinition("repository-query", "mcp-v1");
        McpServerDefinition changed = mcpDefinition("repository-query", "mcp-v2");
        AgentExecutionPlan plan = plan(primary, binding(null, snapshotted));
        RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace =
                new RuntimeWorkspaceMaterializer(tempDir.resolve("runtime-changed-mcp"))
                        .materialize(plan);
        int[] secretResolutions = new int[]{0};
        RuntimeCapabilityMaterializer materializer =
                new RuntimeCapabilityMaterializer(
                        Collections::emptyList,
                        () -> Collections.singletonList(changed),
                        reference -> {
                            secretResolutions[0]++;
                            return "must-not-resolve".toCharArray();
                        });

        assertThrows(IllegalStateException.class,
                () -> materializer.materialize(plan, workspace));

        assertEquals(0, secretResolutions[0]);
        assertFalse(Files.exists(workspace.getExecutionRoot().resolve("capabilities")));
    }

    @Test
    void rejectsWriteMcpToolsWhenSnapshotOnlyAuthorizesRead() throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-read-only"));
        McpServerDefinition writeOnly = new McpServerDefinition(
                "write-only", "1.0.0", "write only",
                set("IMPLEMENT_TEST"), set("CODEX"),
                Arrays.asList("write-mcp", "--stdio"),
                Collections.singletonList(new McpCapability(
                        "write_repository", McpCapabilityType.TOOL,
                        CapabilityAccess.WRITE)),
                Collections.<McpSecretReference>emptyList(),
                10, 30, CanonicalHashing.sha256("write-only"));
        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                "policy@1", "profile", "1.0.0",
                CanonicalHashing.sha256("profile"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new ResolvedMcpServerBinding(
                        writeOnly.getId(), writeOnly.getVersion(),
                        writeOnly.getConfigurationHash(), CapabilityAccess.READ,
                        "STDIO")),
                Collections.emptyList(), "CODEX");
        AgentExecutionPlan plan = plan(primary, binding);
        RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace =
                new RuntimeWorkspaceMaterializer(tempDir.resolve("runtime-read-only"))
                        .materialize(plan);

        assertThrows(IllegalStateException.class,
                () -> new RuntimeCapabilityMaterializer(
                        Collections::emptyList,
                        () -> Collections.singletonList(writeOnly),
                        reference -> new char[0]).materialize(plan, workspace));
    }

    @Test
    void neverExposesResolvedSecretStorageThroughPublicGetter() {
        assertFalse(Arrays.stream(RuntimeCapabilityMaterialization.class.getMethods())
                .anyMatch(method -> "getSecrets".equals(method.getName())));
    }

    private AgentExecutionPlan plan(Path primary, ResolvedCapabilityBinding binding) {
        String prompt = "bounded workbench runtime";
        return new AgentExecutionPlan(
                new ExecutionIdentity("exec-capability", "owner-1", "workbench:wb-1"),
                new RuntimeSelection(AgentType.CODEX, RuntimeVersionPolicy.configured()),
                new PromptPayload(prompt, CanonicalHashing.sha256(prompt),
                        HistoryDelivery.PROMPT_PREFIX),
                new WorkspaceLayout(primary.toString(),
                        Collections.singletonList(primary.toString()),
                        Collections.<String>emptyList(), SandboxMode.READ_ONLY),
                binding,
                new RuntimeLimits(Duration.ofSeconds(5L), 1024L * 1024L));
    }

    private ResolvedCapabilityBinding binding(
            SkillPackage skill, McpServerDefinition mcp) {
        List<ResolvedSkillBinding> skills = skill == null
                ? Collections.<ResolvedSkillBinding>emptyList()
                : Collections.singletonList(new ResolvedSkillBinding(
                        skill.getManifest().getId(), skill.getManifest().getVersion(),
                        skill.getManifest().getTrustSource().name(), skill.getPackageHash(),
                        skill.getManifest().getTrustSource().name()));
        List<ResolvedMcpServerBinding> servers = mcp == null
                ? Collections.<ResolvedMcpServerBinding>emptyList()
                : Collections.singletonList(new ResolvedMcpServerBinding(
                        mcp.getId(), mcp.getVersion(), mcp.getConfigurationHash(),
                        CapabilityAccess.READ, "STDIO"));
        return ResolvedCapabilityBinding.resolve(
                "policy@1", "profile", "1.0.0",
                CanonicalHashing.sha256("profile"),
                Collections.emptyList(), skills, servers,
                Collections.emptyList(), "CODEX");
    }

    private SkillPackage skillPackage(String id, String contentSeed) {
        SkillManifest manifest = new SkillManifest(
                id, "1.0.0", id + " description",
                set("IMPLEMENT_TEST"), set("java"), set(id), "SKILL.md",
                set("references/rules.md"), Collections.emptyList(),
                Collections.emptySet(), set("CODEX"), SkillTrustSource.PLATFORM,
                Collections.emptyList());
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        hashes.put("manifest.yml", CanonicalHashing.sha256(contentSeed + "-manifest"));
        hashes.put("SKILL.md", CanonicalHashing.sha256("# java-tdd exact"));
        hashes.put("references/rules.md", CanonicalHashing.sha256("exact rules"));
        Map<String, byte[]> resources = new LinkedHashMap<String, byte[]>();
        resources.put("references/rules.md",
                "exact rules".getBytes(StandardCharsets.UTF_8));
        return new SkillPackage(manifest, CanonicalHashing.sha256(contentSeed),
                "# java-tdd exact", hashes, resources);
    }

    private McpServerDefinition mcpDefinition(String id, String hashSeed) {
        return new McpServerDefinition(
                id, "1.0.0", id + " description",
                set("IMPLEMENT_TEST"), set("CODEX"),
                Arrays.asList("repository-mcp", "--stdio"),
                Arrays.asList(
                        new McpCapability("read_repository", McpCapabilityType.TOOL,
                                CapabilityAccess.READ),
                        new McpCapability("write_repository", McpCapabilityType.TOOL,
                                CapabilityAccess.WRITE)),
                Collections.singletonList(new McpSecretReference(
                        "REPOSITORY_QUERY_API_KEY", "REPOSITORY_QUERY_TOKEN")),
                10, 30, CanonicalHashing.sha256(hashSeed));
    }

    private LinkedHashSet<String> set(String value) {
        return new LinkedHashSet<String>(Collections.singleton(value));
    }

    private void assertOverride(List<String> command, String expected) {
        for (int index = 0; index + 1 < command.size(); index++) {
            if ("-c".equals(command.get(index))
                    && expected.equals(command.get(index + 1))) {
                return;
            }
        }
        throw new AssertionError("missing -c override: " + expected + " in " + command);
    }

    private String tomlEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
