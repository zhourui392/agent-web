package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.McpSecretReference;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.McpToolAuthorization;
import com.example.agentweb.domain.capability.McpTransport;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.capability.SkillPackage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将 Snapshot 中选中的 Skill/MCP 与可信 Catalog exact 重验后物化到单次 Runtime。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeCapabilityMaterializer {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    private static final Pattern ENVIRONMENT_VARIABLE =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,159}");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final SkillCatalog skillCatalog;
    private final McpServerCatalog mcpServerCatalog;
    private final RuntimeSecretResolver secretResolver;

    public RuntimeCapabilityMaterializer(
            SkillCatalog skillCatalog, McpServerCatalog mcpServerCatalog,
            RuntimeSecretResolver secretResolver) {
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog");
        this.mcpServerCatalog = Objects.requireNonNull(
                mcpServerCatalog, "mcpServerCatalog");
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
    }

    public RuntimeCapabilityMaterialization materialize(
            AgentExecutionPlan plan,
            RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace) {
        if (plan == null || workspace == null) {
            throw new IllegalArgumentException(
                    "runtime plan and materialized workspace are required");
        }
        Path capabilityRoot = workspace.getExecutionRoot().resolve("capabilities");
        Map<String, char[]> secrets = new LinkedHashMap<String, char[]>();
        try {
            List<SelectedSkill> selectedSkills = selectSkills(plan);
            List<SelectedMcp> selectedMcpServers = selectMcpServers(plan);
            secrets = resolveSecrets(selectedMcpServers);
            List<RuntimeCapabilityMaterialization.MaterializedSkill> skills =
                    writeSkills(capabilityRoot, selectedSkills);
            List<RuntimeCapabilityMaterialization.MaterializedMcpServer> mcpServers =
                    materializedMcpServers(selectedMcpServers);
            RuntimeCapabilityMaterialization result =
                    new RuntimeCapabilityMaterialization(
                            plan.getCapabilityBinding().getBindingHash(),
                            skills, mcpServers, secrets);
            clearSecrets(secrets);
            return result;
        } catch (IOException | RuntimeException failure) {
            clearSecrets(secrets);
            new RuntimeCleanup().deleteRecursively(capabilityRoot);
            throw new IllegalStateException(
                    "runtime capabilities could not be materialized", failure);
        }
    }

    private List<SelectedSkill> selectSkills(AgentExecutionPlan plan) {
        Map<String, SkillPackage> discovered = indexSkills(skillCatalog.discover());
        List<SelectedSkill> selected = new ArrayList<SelectedSkill>();
        String runtime = plan.getRuntimeSelection().getAgentType().name();
        for (ResolvedSkillBinding binding : plan.getCapabilityBinding().getSkills()) {
            SkillPackage skill = discovered.get(key(binding.getId(), binding.getVersion()));
            if (skill == null
                    || !binding.getPackageHash().equals(skill.getPackageHash())
                    || !binding.getSource().equals(
                    skill.getManifest().getTrustSource().name())
                    || !binding.getTrustTier().equals(
                    skill.getManifest().getTrustSource().name())
                    || !skill.getManifest().getCompatibleRuntimes().contains(runtime)) {
                throw new IllegalStateException(
                        "selected Skill no longer matches its Runtime snapshot");
            }
            if (!skill.getResourceContents().keySet().equals(
                    skill.getManifest().getResourcePaths())) {
                throw new IllegalStateException(
                        "selected Skill package content is incomplete");
            }
            selected.add(new SelectedSkill(binding, skill));
        }
        return selected;
    }

    private List<SelectedMcp> selectMcpServers(AgentExecutionPlan plan) {
        Map<String, McpServerDefinition> discovered =
                indexMcpServers(mcpServerCatalog.discover());
        List<SelectedMcp> selected = new ArrayList<SelectedMcp>();
        String runtime = plan.getRuntimeSelection().getAgentType().name();
        for (ResolvedMcpServerBinding binding
                : plan.getCapabilityBinding().getMcpServers()) {
            McpServerDefinition definition = discovered.get(
                    key(binding.getId(), binding.getVersion()));
            if (definition == null
                    || !binding.getDefinitionHash().equals(
                    definition.getConfigurationHash())
                    || !definition.getTransport().name().equals(binding.getTransport())
                    || !definition.getCompatibleRuntimes().contains(runtime)
                    || definition.hasUnsupportedResourceCapability()) {
                throw new IllegalStateException(
                        "selected MCP Server no longer matches its Runtime snapshot");
            }
            SelectedMcp mcp = authorizeMcp(binding, definition);
            selected.add(mcp);
        }
        return selected;
    }

    private SelectedMcp authorizeMcp(
            ResolvedMcpServerBinding binding,
            McpServerDefinition definition) {
        if (binding.getAccess() == CapabilityAccess.WRITE
                && definition.getMaximumAccess() != CapabilityAccess.WRITE) {
            throw new IllegalStateException(
                    "selected MCP access exceeds its Catalog definition");
        }
        if (definition.getCapabilities().isEmpty()) {
            return new SelectedMcp(definition,
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList());
        }
        McpToolAuthorization authorization = definition.authorizeTools(
                binding.getAccess());
        return new SelectedMcp(definition,
                authorization.getEnabledToolNames(),
                authorization.getDisabledToolNames());
    }

    private Map<String, char[]> resolveSecrets(List<SelectedMcp> selected) {
        Map<String, char[]> secrets = new LinkedHashMap<String, char[]>();
        try {
            for (SelectedMcp mcp : selected) {
                for (McpSecretReference reference
                        : mcp.definition.getSecretReferences()) {
                    String environmentVariable = requireEnvironmentVariable(
                            reference.getEnvironmentVariable());
                    if (secrets.containsKey(environmentVariable)) {
                        throw new IllegalStateException(
                                "MCP Secret environment variable is duplicated");
                    }
                    char[] value = secretResolver.resolve(reference.getReference());
                    if (value == null || value.length == 0) {
                        throw new IllegalStateException(
                                "required MCP Secret reference is unavailable");
                    }
                    secrets.put(environmentVariable, value.clone());
                    java.util.Arrays.fill(value, '\0');
                }
            }
            return secrets;
        } catch (RuntimeException failure) {
            clearSecrets(secrets);
            throw failure;
        }
    }

    private List<RuntimeCapabilityMaterialization.MaterializedSkill> writeSkills(
            Path capabilityRoot, List<SelectedSkill> selected) throws IOException {
        if (selected.isEmpty()) {
            return Collections.emptyList();
        }
        Path root = capabilityRoot.resolve("skills");
        createSecureDirectory(capabilityRoot);
        createSecureDirectory(root);
        List<RuntimeCapabilityMaterialization.MaterializedSkill> materialized =
                new ArrayList<RuntimeCapabilityMaterialization.MaterializedSkill>();
        for (SelectedSkill selectedSkill : selected) {
            String id = requireIdentifier(selectedSkill.binding.getId(), "Skill id");
            String version = requireIdentifier(
                    selectedSkill.binding.getVersion(), "Skill version");
            Path packageRoot = root.resolve(id).resolve(version).normalize();
            if (!packageRoot.startsWith(root)) {
                throw new IllegalStateException("Skill package path escapes Runtime root");
            }
            createSecureDirectory(root.resolve(id));
            createSecureDirectory(packageRoot);
            Path entry = writePackageFile(packageRoot,
                    selectedSkill.skill.getManifest().getEntryPath(),
                    selectedSkill.skill.getEntryContent().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> resource
                    : selectedSkill.skill.getResourceContents().entrySet()) {
                writePackageFile(packageRoot, resource.getKey(), resource.getValue());
            }
            materialized.add(
                    new RuntimeCapabilityMaterialization.MaterializedSkill(
                            id, version, entry));
        }
        return materialized;
    }

    private List<RuntimeCapabilityMaterialization.MaterializedMcpServer>
    materializedMcpServers(List<SelectedMcp> selected) {
        List<RuntimeCapabilityMaterialization.MaterializedMcpServer> result =
                new ArrayList<RuntimeCapabilityMaterialization.MaterializedMcpServer>();
        for (SelectedMcp mcp : selected) {
            List<String> variables = new ArrayList<String>();
            for (McpSecretReference reference
                    : mcp.definition.getSecretReferences()) {
                variables.add(requireEnvironmentVariable(
                        reference.getEnvironmentVariable()));
            }
            Collections.sort(variables);
            result.add(new RuntimeCapabilityMaterialization.MaterializedMcpServer(
                    requireIdentifier(mcp.definition.getId(), "MCP Server id"),
                    requireIdentifier(mcp.definition.getVersion(), "MCP Server version"),
                    mcp.definition.getTransport(),
                    safeCommand(mcp.definition),
                    safeOptionalValue(mcp.definition.getWorkingDirectory(),
                            "MCP working directory"),
                    safeOptionalValue(mcp.definition.getEndpoint(), "MCP endpoint"),
                    variables, true,
                    mcp.definition.getStartupTimeoutSeconds(),
                    mcp.definition.getToolTimeoutSeconds(),
                    mcp.enabledToolNames, mcp.disabledToolNames));
        }
        result.sort(Comparator.comparing(
                RuntimeCapabilityMaterialization.MaterializedMcpServer::getId));
        return result;
    }

    private List<String> safeCommand(McpServerDefinition definition) {
        if (definition.getTransport() == McpTransport.STREAMABLE_HTTP) {
            return Collections.emptyList();
        }
        return safeTokens(definition.getCommand());
    }

    private String safeOptionalValue(String value, String name) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() > 4096 || containsControlCharacter(value)) {
            throw new IllegalStateException(name + " is unsafe");
        }
        return value;
    }

    private Path writePackageFile(Path packageRoot, String relativePath, byte[] bytes)
            throws IOException {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalStateException("Skill package path must not be blank");
        }
        Path relative = java.nio.file.Paths.get(relativePath.trim());
        if (relative.isAbsolute() || containsNativeInstruction(relative)) {
            throw new IllegalStateException("Skill package path is forbidden");
        }
        Path target = packageRoot.resolve(relative).normalize();
        if (!target.startsWith(packageRoot)) {
            throw new IllegalStateException("Skill package path escapes Runtime root");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            secureDirectory(parent);
        }
        Files.write(target, bytes);
        secureFile(target);
        return target;
    }

    private Map<String, SkillPackage> indexSkills(List<SkillPackage> packages) {
        if (packages == null || packages.contains(null)) {
            throw new IllegalStateException("Skill Catalog result is invalid");
        }
        Map<String, SkillPackage> indexed = new HashMap<String, SkillPackage>();
        for (SkillPackage skill : packages) {
            String key = key(skill.getManifest().getId(),
                    skill.getManifest().getVersion());
            if (indexed.put(key, skill) != null) {
                throw new IllegalStateException("Skill Catalog contains duplicate version");
            }
        }
        return indexed;
    }

    private Map<String, McpServerDefinition> indexMcpServers(
            List<McpServerDefinition> definitions) {
        if (definitions == null || definitions.contains(null)) {
            throw new IllegalStateException("MCP Catalog result is invalid");
        }
        Map<String, McpServerDefinition> indexed =
                new HashMap<String, McpServerDefinition>();
        for (McpServerDefinition definition : definitions) {
            String key = key(definition.getId(), definition.getVersion());
            if (indexed.put(key, definition) != null) {
                throw new IllegalStateException("MCP Catalog contains duplicate version");
            }
        }
        return indexed;
    }

    private String key(String id, String version) {
        return id + '\0' + version;
    }

    private String requireIdentifier(String value, String name) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalStateException(name + " is unsafe for Runtime materialization");
        }
        return value;
    }

    private String requireEnvironmentVariable(String value) {
        if (value == null || !ENVIRONMENT_VARIABLE.matcher(value).matches()) {
            throw new IllegalStateException(
                    "MCP Secret environment variable is unsafe");
        }
        return value;
    }

    private List<String> safeTokens(List<String> values) {
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty() || value.length() > 1000
                    || containsControlCharacter(value)) {
                throw new IllegalStateException("MCP command token is unsafe");
            }
            result.add(value);
        }
        return result;
    }

    private boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsNativeInstruction(Path relative) {
        for (Path element : relative) {
            String name = element.toString();
            if ("AGENTS.md".equalsIgnoreCase(name)
                    || "CLAUDE.md".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void createSecureDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        secureDirectory(directory);
    }

    private void secureDirectory(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Windows 权限由运行服务账户边界承担。
        }
    }

    private void secureFile(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Windows 权限由运行服务账户边界承担。
        }
    }

    private void clearSecrets(Map<String, char[]> secrets) {
        for (char[] value : secrets.values()) {
            java.util.Arrays.fill(value, '\0');
        }
        secrets.clear();
    }

    private static final class SelectedSkill {

        private final ResolvedSkillBinding binding;
        private final SkillPackage skill;

        private SelectedSkill(ResolvedSkillBinding binding, SkillPackage skill) {
            this.binding = binding;
            this.skill = skill;
        }
    }

    private static final class SelectedMcp {

        private final McpServerDefinition definition;
        private final List<String> enabledToolNames;
        private final List<String> disabledToolNames;

        private SelectedMcp(
                McpServerDefinition definition, List<String> enabledToolNames,
                List<String> disabledToolNames) {
            this.definition = definition;
            this.enabledToolNames = enabledToolNames;
            this.disabledToolNames = disabledToolNames;
        }
    }
}
