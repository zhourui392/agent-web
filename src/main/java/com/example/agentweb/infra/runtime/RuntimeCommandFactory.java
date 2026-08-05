package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.domain.capability.McpTransport;
import com.example.agentweb.domain.shared.AgentType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 根据完整执行计划生成不经 Shell 拼接的 Provider 命令 token。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeCommandFactory {

    private static final char NEWLINE = '\n';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char NULL_CHARACTER = '\0';

    private final String codexCommand;
    private final boolean sandboxBypass;

    public RuntimeCommandFactory(String codexCommand) {
        this(codexCommand, false);
    }

    public RuntimeCommandFactory(String codexCommand, boolean sandboxBypass) {
        this.codexCommand = requireSafeToken(codexCommand, "Codex command");
        this.sandboxBypass = sandboxBypass;
    }

    public List<String> create(
            AgentExecutionPlan plan,
            RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace) {
        if (plan != null && (!plan.getCapabilityBinding().getSkills().isEmpty()
                || !plan.getCapabilityBinding().getMcpServers().isEmpty())) {
            throw new IllegalStateException(
                    "selected Runtime capabilities must be materialized");
        }
        return create(plan, workspace,
                RuntimeCapabilityMaterialization.empty(
                        plan == null ? "missing" : plan.getCapabilityBinding().getBindingHash()));
    }

    public List<String> create(
            AgentExecutionPlan plan,
            RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace,
            RuntimeCapabilityMaterialization capabilities) {
        if (plan == null || workspace == null) {
            throw new IllegalArgumentException("runtime plan and workspace are required");
        }
        if (capabilities == null || !plan.getCapabilityBinding().getBindingHash()
                .equals(capabilities.getBindingHash())) {
            throw new IllegalStateException(
                    "materialized capabilities do not match Runtime plan");
        }
        if (plan.getRuntimeSelection().getAgentType() != AgentType.CODEX) {
            throw new IllegalStateException("process Runtime currently supports Codex only");
        }
        List<String> command = createCommonRuntimeCodexJsonPreamble();
        SandboxMode sandboxMode = plan.getWorkspaceLayout().getSandboxMode();
        if (!sandboxBypass) {
            Collections.addAll(command, "--sandbox", sandboxToken(sandboxMode));
        }
        command.add("-C");
        command.add(workspace.getPrimaryRepositoryRoot().toString());
        List<Path> additionalRoots = workspace.getReadableRoots();
        if (sandboxMode == SandboxMode.WORKSPACE_WRITE) {
            if (!workspace.getWritableRoots().contains(workspace.getPrimaryRepositoryRoot())) {
                throw new IllegalStateException(
                        "workspace-write Codex runtime requires a writable primary repository");
            }
            additionalRoots = workspace.getWritableRoots();
        }
        for (Path root : additionalRoots) {
            if (!root.equals(workspace.getPrimaryRepositoryRoot())) {
                Collections.addAll(command, "--add-dir", root.toString());
            }
        }
        addSkillOverrides(command, capabilities.getSkills());
        addMcpOverrides(command, capabilities.getMcpServers());
        command.add("-");
        return Collections.unmodifiableList(command);
    }

    private List<String> createCommonRuntimeCodexJsonPreamble() {
        List<String> command = new ArrayList<String>();
        command.add(codexCommand);
        if (sandboxBypass) {
            Collections.addAll(command, "--dangerously-bypass-approvals-and-sandbox", "exec");
        } else {
            Collections.addAll(command, "--ask-for-approval", "never", "exec");
        }
        addOverride(command, "allow_login_shell=false");
        Collections.addAll(command, "--ephemeral", "--json");
        return command;
    }

    private String sandboxToken(SandboxMode sandboxMode) {
        if (sandboxMode == SandboxMode.READ_ONLY) {
            return "read-only";
        }
        if (sandboxMode == SandboxMode.WORKSPACE_WRITE) {
            return "workspace-write";
        }
        throw new IllegalStateException("unsupported Runtime sandbox mode");
    }

    private void addSkillOverrides(
            List<String> command,
            List<RuntimeCapabilityMaterialization.MaterializedSkill> skills) {
        if (skills.isEmpty()) {
            return;
        }
        StringBuilder value = new StringBuilder("skills.config=[");
        for (int index = 0; index < skills.size(); index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append("{path=")
                    .append(quoted(skills.get(index).getEntryPath().toString()))
                    .append(",enabled=true}");
        }
        value.append(']');
        addOverride(command, value.toString());
    }

    private void addMcpOverrides(
            List<String> command,
            List<RuntimeCapabilityMaterialization.MaterializedMcpServer> servers) {
        for (RuntimeCapabilityMaterialization.MaterializedMcpServer server : servers) {
            String id = requireBareKey(server.getId());
            String prefix = "mcp_servers." + id + ".";
            addMcpTransportOverrides(command, prefix, server);
            addOverride(command, prefix + "required=" + server.isRequired());
            addOverride(command, prefix + "startup_timeout_sec="
                    + server.getStartupTimeoutSeconds());
            addOverride(command, prefix + "tool_timeout_sec="
                    + server.getToolTimeoutSeconds());
            if (!server.getEnabledToolNames().isEmpty()
                    || !server.getDisabledToolNames().isEmpty()) {
                addOverride(command, prefix + "enabled_tools="
                        + array(server.getEnabledToolNames()));
                addOverride(command, prefix + "disabled_tools="
                        + array(server.getDisabledToolNames()));
            }
            addOverride(command, prefix
                    + "default_tools_approval_mode=\"writes\"");
        }
    }

    private void addMcpTransportOverrides(
            List<String> command, String prefix,
            RuntimeCapabilityMaterialization.MaterializedMcpServer server) {
        if (server.getTransport() == McpTransport.STDIO) {
            if (server.getCommand().isEmpty()) {
                throw new IllegalStateException("STDIO MCP command is required");
            }
            addOverride(command, prefix + "command="
                    + quoted(server.getCommand().get(0)));
            addOverride(command, prefix + "args="
                    + array(server.getCommand().subList(1, server.getCommand().size())));
            addOverride(command, prefix + "env_vars="
                    + array(server.getSecretEnvironmentVariables()));
            if (!server.getWorkingDirectory().isEmpty()) {
                addOverride(command, prefix + "cwd="
                        + quoted(server.getWorkingDirectory()));
            }
            return;
        }
        if (server.getTransport() != McpTransport.STREAMABLE_HTTP
                || server.getEndpoint().isEmpty()) {
            throw new IllegalStateException("unsupported MCP transport configuration");
        }
        addOverride(command, prefix + "url=" + quoted(server.getEndpoint()));
        if (server.getSecretEnvironmentVariables().size() > 1) {
            throw new IllegalStateException(
                    "STREAMABLE_HTTP MCP supports one bearer Secret reference");
        }
        if (!server.getSecretEnvironmentVariables().isEmpty()) {
            addOverride(command, prefix + "bearer_token_env_var="
                    + quoted(server.getSecretEnvironmentVariables().get(0)));
        }
    }

    private void addOverride(List<String> command, String value) {
        command.add("-c");
        command.add(value);
    }

    private String array(List<String> values) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(quoted(values.get(index)));
        }
        return result.append(']').toString();
    }

    private String quoted(String value) {
        if (value == null || value.length() > 4096) {
            throw new IllegalStateException("Runtime TOML value is invalid");
        }
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || character == '\"') {
                result.append('\\').append(character);
            } else if (Character.isISOControl(character)) {
                throw new IllegalStateException(
                        "Runtime TOML value contains a control character");
            } else {
                result.append(character);
            }
        }
        return result.append('\"').toString();
    }

    private String requireBareKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,119}")) {
            throw new IllegalStateException("Runtime TOML key is unsafe");
        }
        return value;
    }

    private static String requireSafeToken(String value, String name) {
        if (value == null || value.trim().isEmpty()
                || value.indexOf(NEWLINE) >= 0 || value.indexOf(CARRIAGE_RETURN) >= 0
                || value.indexOf(NULL_CHARACTER) >= 0) {
            throw new IllegalArgumentException(name + " is unsafe");
        }
        return value;
    }
}
