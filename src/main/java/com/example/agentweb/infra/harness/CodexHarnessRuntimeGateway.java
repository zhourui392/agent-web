package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.port.AgentExecutionSpec;
import com.example.agentweb.app.harness.port.AgentRuntimeGateway;
import com.example.agentweb.app.harness.port.AgentRuntimeStartException;
import com.example.agentweb.app.harness.port.RuntimeEvent;
import com.example.agentweb.app.harness.port.RuntimeEventSink;
import com.example.agentweb.app.harness.port.RuntimePreflightReport;
import com.example.agentweb.app.harness.port.RuntimeEvidenceStore;
import com.example.agentweb.app.harness.port.RuntimePreflightGateway;
import com.example.agentweb.config.harness.HarnessRuntimeProperties;
import com.example.agentweb.config.harness.HarnessSecurityProperties;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.McpSecretReference;
import com.example.agentweb.domain.harness.RuntimeEnforcementProfile;
import com.example.agentweb.domain.harness.RuntimeExecutionSignal;
import com.example.agentweb.domain.harness.RuntimeArtifactBundle;
import com.example.agentweb.domain.harness.RuntimeCommandObservation;
import com.example.agentweb.domain.harness.RuntimeProducedArtifact;
import com.example.agentweb.domain.harness.SelectedMcpServer;
import com.example.agentweb.domain.harness.WorkspaceBoundaryKind;
import com.example.agentweb.domain.harness.WorkspaceRepoSkill;
import com.example.agentweb.domain.harness.WorkspaceRuntimeInventory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Harness 专用 Codex Runtime Adapter：隔离配置、最小环境、JSONL、终止和幂等清理。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class CodexHarnessRuntimeGateway implements AgentRuntimeGateway,
        RuntimePreflightGateway, AutoCloseable {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final String ADAPTER_VERSION = "codex-harness-adapter@1";
    private static final String TURN_FAILED_EVENT = "turn.failed";
    private static final String ENFORCEMENT_PROFILE_VERSION = "codex-runtime-enforcement@1";
    private static final String SAFE_IDENTIFIER_PATTERN = "[A-Za-z0-9_-]+";
    private static final String SAFE_ENVIRONMENT_VARIABLE_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String CODEX_PROVIDER_CREDENTIAL_ENVIRONMENT_VARIABLE = "OPENAI_API_KEY";
    private static final Pattern CODEX_VERSION_PATTERN = Pattern.compile(
            "codex-cli[ \\t]+([0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?)");
    private static final char NEWLINE = '\n';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char NULL_CHARACTER = '\0';
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HarnessRuntimeProperties runtimeProperties;
    private final HarnessSecurityProperties securityProperties;
    private final HarnessSecretResolver secretResolver;
    private final RuntimeEvidenceStore evidenceStore;
    private final Clock clock;
    private final TaskExecutor monitorExecutor;
    private final Map<String, ActiveExecution> activeExecutions =
            new ConcurrentHashMap<String, ActiveExecution>();

    public CodexHarnessRuntimeGateway(HarnessRuntimeProperties runtimeProperties,
                                     HarnessSecurityProperties securityProperties,
                                     HarnessSecretResolver secretResolver,
                                     RuntimeEvidenceStore evidenceStore,
                                     @Qualifier("harnessRuntimeTaskExecutor") TaskExecutor monitorExecutor,
                                     Clock clock) {
        this.runtimeProperties = runtimeProperties;
        this.securityProperties = securityProperties;
        this.secretResolver = secretResolver;
        this.evidenceStore = evidenceStore;
        this.monitorExecutor = monitorExecutor;
        this.clock = clock;
    }

    @Override
    public RuntimePreflightReport preflight(AgentRuntime runtime, String workingDir) {
        return inspectPreflight(runtime, HarnessStage.ANALYSIS, workingDir).getReport();
    }

    @Override
    public RuntimePreflightReport preflight(AgentRuntime runtime, HarnessStage stage,
                                            String workingDir) {
        return inspectPreflight(runtime, stage, workingDir).getReport();
    }

    @Override
    public void start(AgentExecutionSpec spec, RuntimeEventSink eventSink) {
        requireLaunchable(spec, eventSink);
        PreflightInspection preflight = inspectPreflight(
                spec.getRuntime(), spec.getStage(), spec.getWorkingDir());
        requireUnchangedPreflight(spec, preflight.getReport());
        Path executionRoot = runtimeRoot().resolve("exec-"
                + com.example.agentweb.domain.harness.HarnessHashing.sha256(
                spec.getExecutionId()).substring(0, 24));
        ActiveExecution active = null;
        try {
            Files.createDirectories(executionRoot);
            secureDirectory(executionRoot);
            Path outputSchema = executionRoot.resolve("artifact-bundle-schema.json");
            Path outputLastMessage = executionRoot.resolve("artifact-bundle.json");
            writeArtifactBundleSchema(outputSchema, spec);
            Files.write(outputLastMessage, new byte[0]);
            secureFile(outputLastMessage);
            List<String> secrets = new ArrayList<String>();
            ProcessBuilder processBuilder = new ProcessBuilder(command(spec,
                    preflight.getWorkspace().getBoundaryRoot(), outputSchema, outputLastMessage));
            processBuilder.directory(Paths.get(spec.getWorkingDir()).toFile());
            processBuilder.redirectErrorStream(true);
            applyEnvironment(processBuilder, executionRoot, spec.getSelectedMcpServers(), secrets);
            Process process = processBuilder.start();
            String handle;
            try {
                handle = runtimeHandle(process);
            } catch (RuntimeException ex) {
                destroyProcessTree(process);
                throw ex;
            }
            active = new ActiveExecution(spec, eventSink, process, executionRoot, secrets,
                    outputLastMessage, handle, System.nanoTime());
            ActiveExecution previous = activeExecutions.putIfAbsent(spec.getExecutionId(), active);
            if (previous != null) {
                destroyProcessTree(process);
                throw new IllegalStateException("runtime execution is already active");
            }
            writePrompt(process, spec.getPrompt());
            active.emitStarted(clock.instant());
            ActiveExecution monitored = active;
            monitorExecutor.execute(() -> monitor(monitored));
        } catch (Exception ex) {
            if (active != null) {
                activeExecutions.remove(spec.getExecutionId(), active);
                destroyProcessTree(active.getProcess());
            }
            boolean cleaned = cleanup(executionRoot);
            throw new AgentRuntimeStartException("could not start isolated Codex runtime",
                    cleaned, ex);
        }
    }

    @Override
    public void cancel(String executionId) {
        ActiveExecution active = activeExecutions.get(executionId);
        if (active == null) {
            return;
        }
        active.requestCancellation();
        destroyProcessTree(active.getProcess());
    }

    @Override
    @PreDestroy
    public void close() {
        for (ActiveExecution active : activeExecutions.values()) {
            active.requestCancellation();
            destroyProcessTree(active.getProcess());
        }
        for (ActiveExecution active : activeExecutions.values()) {
            cleanup(active.getExecutionRoot());
        }
        activeExecutions.clear();
    }

    private void monitor(ActiveExecution active) {
        OutputState outputState = new OutputState();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                active.getProcess().getInputStream(), StandardCharsets.UTF_8))) {
            TerminalOutcome outcome = awaitOutcome(active, reader, outputState);
            RuntimeArtifactBundle artifactBundle = null;
            if (outcome.getKind() == TerminalKind.SUCCEEDED) {
                try {
                    artifactBundle = readArtifactBundle(active);
                } catch (RuntimeException | IOException ex) {
                    outcome = TerminalOutcome.failed(
                            "runtime artifact bundle validation failed", outcome.getExitCode());
                }
            }
            String evidenceReference = persistEvidence(active);
            if (evidenceReference == null) {
                outcome = outcome.evidencePersistenceFailed();
            }
            boolean cleaned = cleanup(active.getExecutionRoot());
            active.emitTerminal(outcome.getKind(), outcome.getExitCode(), outcome.getReason(),
                    evidenceReference, cleaned, artifactBundle, clock.instant());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            destroyProcessTree(active.getProcess());
            String evidenceReference = persistEvidence(active);
            boolean cleaned = cleanup(active.getExecutionRoot());
            active.emitTerminal(TerminalKind.CANCELLED, null,
                    "runtime monitor interrupted", evidenceReference, cleaned, null, clock.instant());
        } catch (IOException ex) {
            destroyProcessTree(active.getProcess());
            String evidenceReference = persistEvidence(active);
            boolean cleaned = cleanup(active.getExecutionRoot());
            active.emitTerminal(active.isCancellationRequested()
                            ? TerminalKind.CANCELLED : TerminalKind.FAILED,
                    null, "runtime stream failed", evidenceReference, cleaned, null, clock.instant());
        } catch (RuntimeException ex) {
            destroyProcessTree(active.getProcess());
            String evidenceReference = persistEvidence(active);
            boolean cleaned = cleanup(active.getExecutionRoot());
            active.emitTerminal(TerminalKind.FAILED, null,
                    "runtime event callback failed", evidenceReference, cleaned, null, clock.instant());
        } finally {
            activeExecutions.remove(active.getSpec().getExecutionId(), active);
        }
    }

    private TerminalOutcome awaitOutcome(ActiveExecution active, BufferedReader reader,
                                         OutputState outputState)
            throws IOException, InterruptedException {
        TerminalOutcome outcome = watchProcess(active, reader, outputState);
        drain(reader, active, outputState);
        int exitCode = waitForExit(active.getProcess());
        if (outcome != null) {
            return outcome.withExitCode(exitCode);
        }
        if (outputState.getBytes() > runtimeProperties.getMaxOutputBytes()) {
            return TerminalOutcome.failed("runtime output limit exceeded", exitCode);
        }
        if (active.isCancellationRequested()) {
            return TerminalOutcome.cancelled("runtime cancellation confirmed", exitCode);
        }
        if (exitCode == 0 && !outputState.isTurnFailed()) {
            return TerminalOutcome.succeeded(exitCode);
        }
        return TerminalOutcome.failed("runtime process failed", exitCode);
    }

    private TerminalOutcome watchProcess(ActiveExecution active, BufferedReader reader,
                                         OutputState outputState)
            throws IOException, InterruptedException {
        long lastOutputNanos = active.getStartedNanos();
        while (active.getProcess().isAlive()) {
            boolean receivedOutput = false;
            while (reader.ready()) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                consume(line, active, outputState);
                receivedOutput = true;
            }
            if (receivedOutput) {
                lastOutputNanos = System.nanoTime();
            }
            TerminalOutcome stop = stopReason(active, outputState, lastOutputNanos);
            if (stop != null) {
                destroyProcessTree(active.getProcess());
                return stop;
            }
            Thread.sleep(20L);
        }
        return null;
    }

    private TerminalOutcome stopReason(ActiveExecution active, OutputState outputState,
                                       long lastOutputNanos) {
        if (outputState.getBytes() > runtimeProperties.getMaxOutputBytes()) {
            return TerminalOutcome.failed("runtime output limit exceeded", null);
        }
        if (active.isCancellationRequested()) {
            return TerminalOutcome.cancelled("runtime cancellation confirmed", null);
        }
        long now = System.nanoTime();
        if (exceeded(active.getStartedNanos(), now, runtimeProperties.getMaxRuntimeSeconds())) {
            return TerminalOutcome.timedOut("runtime maximum duration exceeded");
        }
        if (exceeded(lastOutputNanos, now, runtimeProperties.getIdleTimeoutSeconds())) {
            return TerminalOutcome.timedOut("runtime idle timeout exceeded");
        }
        return null;
    }

    private void requireLaunchable(AgentExecutionSpec spec, RuntimeEventSink eventSink) {
        if (spec == null || eventSink == null || spec.getRuntime() != AgentRuntime.CODEX) {
            throw new IllegalArgumentException("Codex execution spec and event sink are required");
        }
        RuntimeEnforcementProfile profile = spec.getEnforcementProfile();
        if (!profile.supportsMcpToolIsolation()
                || !profile.isProcessTreeCancellationEnforced()) {
            throw new IllegalStateException("runtime isolation and cancellation are required");
        }
    }

    private List<String> command(AgentExecutionSpec spec, Path workspaceBoundary,
                                 Path outputSchema, Path outputLastMessage) {
        List<String> command = new ArrayList<String>();
        command.add(runtimeProperties.getCodexCommand());
        Collections.addAll(command, "--ask-for-approval", "never", "exec",
                "--ignore-user-config", "--ignore-rules", "--ephemeral", "--json",
                "--output-schema", outputSchema.toString(),
                "--output-last-message", outputLastMessage.toString(),
                "--sandbox", spec.getEnforcementProfile().getSandboxMode(),
                "-C", spec.getWorkingDir());
        addSkillOverrides(command, spec, workspaceBoundary);
        addMcpOverrides(command, spec.getSelectedMcpServers());
        command.add("-");
        return command;
    }

    private void writeArtifactBundleSchema(Path path, AgentExecutionSpec spec) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.putArray("required").add("schemaVersion").add("stage").add("artifacts");
        com.fasterxml.jackson.databind.node.ObjectNode properties = root.putObject("properties");
        properties.putObject("schemaVersion").put("const", RuntimeArtifactBundle.SCHEMA_VERSION);
        properties.putObject("stage").put("const", spec.getStage().name());
        com.fasterxml.jackson.databind.node.ObjectNode artifacts = properties.putObject("artifacts");
        artifacts.put("type", "array");
        artifacts.put("minItems", spec.getRequiredOutputArtifacts().size());
        artifacts.put("maxItems", spec.getRequiredOutputArtifacts().size());
        com.fasterxml.jackson.databind.node.ObjectNode item = artifacts.putObject("items");
        item.put("type", "object");
        item.put("additionalProperties", false);
        item.putArray("required").add("artifactId").add("artifactType")
                .add("contentType").add("classification").add("content");
        com.fasterxml.jackson.databind.node.ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("artifactId").put("type", "string")
                .put("minLength", 1).put("maxLength", 128);
        com.fasterxml.jackson.databind.node.ArrayNode artifactTypes =
                itemProperties.putObject("artifactType").putArray("enum");
        for (ArtifactType type : spec.getRequiredOutputArtifacts()) {
            artifactTypes.add(type.name());
        }
        itemProperties.putObject("contentType").putArray("enum")
                .add("application/json").add("text/markdown").add("text/plain");
        itemProperties.putObject("classification").putArray("enum")
                .add("PUBLIC").add("INTERNAL").add("SENSITIVE");
        itemProperties.putObject("content").put("type", "string").put("minLength", 1);
        Files.write(path, MAPPER.writeValueAsBytes(root));
        secureFile(path);
    }

    private RuntimeArtifactBundle readArtifactBundle(ActiveExecution active) throws IOException {
        Path path = active.getOutputLastMessage();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) < 1L
                || Files.size(path) > runtimeProperties.getMaxOutputBytes()) {
            throw new IllegalStateException("runtime artifact bundle file is invalid");
        }
        JsonNode root = MAPPER.readTree(Files.readAllBytes(path));
        if (root == null || !root.isObject() || !root.path("artifacts").isArray()) {
            throw new IllegalStateException("runtime artifact bundle JSON is invalid");
        }
        String schemaVersion = requiredText(root, "schemaVersion");
        HarnessStage stage = HarnessStage.valueOf(requiredText(root, "stage"));
        List<RuntimeProducedArtifact> artifacts = new ArrayList<RuntimeProducedArtifact>();
        Iterator<JsonNode> values = root.path("artifacts").elements();
        while (values.hasNext()) {
            JsonNode item = values.next();
            artifacts.add(new RuntimeProducedArtifact(requiredText(item, "artifactId"),
                    ArtifactType.valueOf(requiredText(item, "artifactType")),
                    requiredText(item, "contentType"),
                    ArtifactClassification.valueOf(requiredText(item, "classification")),
                    ArtifactContent.from(requiredText(item, "content")
                            .getBytes(StandardCharsets.UTF_8))));
        }
        RuntimeArtifactBundle bundle = RuntimeArtifactBundle.create(schemaVersion, stage,
                artifacts, active.getSpec().getRequiredOutputArtifacts());
        bundle.requireStage(active.getSpec().getStage());
        return bundle;
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            throw new IllegalStateException("runtime artifact bundle field is invalid: " + field);
        }
        return value.asText();
    }

    private void addSkillOverrides(List<String> command, AgentExecutionSpec spec,
                                   Path workspaceBoundary) {
        List<String> relativePaths = spec.getWorkspaceInventory().disabledRepoSkillPaths();
        if (relativePaths.isEmpty()) {
            return;
        }
        StringBuilder value = new StringBuilder("skills.config=[");
        for (int index = 0; index < relativePaths.size(); index++) {
            if (index > 0) {
                value.append(',');
            }
            Path entry = workspaceBoundary.resolve(relativePaths.get(index)).normalize();
            requireInsideBoundary(entry, workspaceBoundary, "repository Skill override");
            try {
                entry = entry.toRealPath();
            } catch (IOException ex) {
                throw new IllegalStateException("repository Skill override is unavailable", ex);
            }
            requireInsideBoundary(entry, workspaceBoundary, "repository Skill override");
            value.append("{path=").append(quoted(entry.toString()))
                    .append(",enabled=false}");
        }
        value.append(']');
        addOverride(command, value.toString());
    }

    private void addMcpOverrides(List<String> command, List<SelectedMcpServer> servers) {
        List<SelectedMcpServer> ordered = new ArrayList<SelectedMcpServer>(servers);
        ordered.sort(Comparator.comparing(SelectedMcpServer::getId)
                .thenComparing(SelectedMcpServer::getVersion));
        for (SelectedMcpServer server : ordered) {
            requireIdentifier(server.getId(), "MCP server id");
            String prefix = "mcp_servers." + server.getId() + ".";
            addOverride(command, prefix + "command=" + quoted(server.getCommand().get(0)));
            addOverride(command, prefix + "args=" + array(server.getCommand().subList(
                    1, server.getCommand().size())));
            List<String> environmentVariables = new ArrayList<String>();
            for (McpSecretReference reference : server.getSecretReferences()) {
                requireEnvironmentVariable(reference.getEnvironmentVariable());
                environmentVariables.add(reference.getEnvironmentVariable());
            }
            addOverride(command, prefix + "env_vars=" + array(environmentVariables));
            addOverride(command, prefix + "required=" + server.isRequired());
            addOverride(command, prefix + "startup_timeout_sec="
                    + server.getStartupTimeoutSeconds());
            addOverride(command, prefix + "tool_timeout_sec="
                    + server.getToolTimeoutSeconds());
            addOverride(command, prefix + "enabled_tools="
                    + array(server.getEnabledToolNames()));
            addOverride(command, prefix + "disabled_tools="
                    + array(server.getDisabledToolNames()));
            addOverride(command, prefix + "default_tools_approval_mode=\"writes\"");
        }
    }

    private void addOverride(List<String> command, String value) {
        command.add("-c");
        command.add(value);
    }

    private void applyEnvironment(ProcessBuilder processBuilder, Path executionRoot,
                                  List<SelectedMcpServer> servers, List<String> resolvedSecrets) {
        applyBaseEnvironment(processBuilder, executionRoot);
        Map<String, String> environment = processBuilder.environment();
        applyProviderCredential(environment, resolvedSecrets);
        for (SelectedMcpServer server : servers) {
            for (McpSecretReference reference : server.getSecretReferences()) {
                requireEnvironmentVariable(reference.getEnvironmentVariable());
                String value = secretResolver.resolve(reference.getReference());
                if (value == null || value.isEmpty()) {
                    throw new IllegalStateException("resolved secret must not be empty");
                }
                environment.put(reference.getEnvironmentVariable(), value);
                resolvedSecrets.add(value);
            }
        }
    }

    private void applyProviderCredential(Map<String, String> environment,
                                         List<String> resolvedSecrets) {
        String reference = runtimeProperties.getProviderCredentialReference();
        if (reference == null || reference.trim().isEmpty()) {
            return;
        }
        String value = secretResolver.resolve(reference.trim());
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("resolved provider credential must not be empty");
        }
        environment.put(CODEX_PROVIDER_CREDENTIAL_ENVIRONMENT_VARIABLE, value);
        resolvedSecrets.add(value);
    }

    private void applyBaseEnvironment(ProcessBuilder processBuilder, Path isolatedRoot) {
        Map<String, String> environment = processBuilder.environment();
        Map<String, String> inherited = new HashMap<String, String>(System.getenv());
        environment.clear();
        for (String name : securityProperties.getInheritedEnvironmentVariables()) {
            if (CODEX_PROVIDER_CREDENTIAL_ENVIRONMENT_VARIABLE.equals(name)) {
                throw new IllegalStateException(
                        "provider credential must use an explicit credential reference");
            }
            String value = inherited.get(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        String isolatedHome = isolatedRoot.toAbsolutePath().toString();
        environment.put("HOME", isolatedHome);
        environment.put("CODEX_HOME", isolatedHome);
        environment.put("XDG_CONFIG_HOME", isolatedHome);
    }

    private void writePrompt(Process process, String prompt) throws IOException {
        try (OutputStream output = process.getOutputStream()) {
            output.write(prompt.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();
        }
    }

    private void drain(BufferedReader reader, ActiveExecution active,
                       OutputState outputState) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            consume(line, active, outputState);
            if (outputState.getBytes() > runtimeProperties.getMaxOutputBytes()) {
                break;
            }
        }
    }

    private void consume(String line, ActiveExecution active, OutputState outputState) {
        byte[] raw = line.getBytes(StandardCharsets.UTF_8);
        outputState.addBytes(raw.length + 1L);
        String type = eventType(line);
        if (TURN_FAILED_EVENT.equals(type)) {
            outputState.markTurnFailed();
        }
        String redacted = redactSecrets(line, active.getSecrets());
        observeCompletedCommand(redacted, active);
        active.emitOutput(boundedEvidenceLine(redacted), outputSummary(type),
                runtimeProperties.getMaxOutputBytes(),
                clock.instant());
    }

    private String persistEvidence(ActiveExecution active) {
        try {
            return evidenceStore.store(active.getSpec(), active.evidenceBytes(), clock.instant());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private PreflightInspection inspectPreflight(AgentRuntime runtime, HarnessStage stage,
                                                 String workingDir) {
        if (runtime != AgentRuntime.CODEX) {
            throw new IllegalStateException("Harness M3 only supports the Codex runtime");
        }
        Path root = runtimeRoot();
        try {
            Files.createDirectories(root);
            secureDirectory(root);
        } catch (IOException ex) {
            throw new IllegalStateException("runtime temporary root is unavailable", ex);
        }
        RuntimeVersion version = probeRuntimeVersion(root);
        WorkspaceInspection workspace = inspectWorkspace(workingDir);
        if (!supportsProcessTreeCancellation()) {
            throw new IllegalStateException("runtime process tree cancellation is unavailable");
        }
        String sandboxMode = sandboxMode(stage);
        RuntimeEnforcementProfile profile = new RuntimeEnforcementProfile(
                ENFORCEMENT_PROFILE_VERSION, ADAPTER_VERSION, version.getDisplayVersion(),
                runtimeProperties.getCompatibilityMatrixVersion(),
                sandboxMode, true, true, true,
                workspace.getInventory().isProjectConfigAbsent(), true, true);
        return new PreflightInspection(new RuntimePreflightReport(
                profile, workspace.getInventory()), workspace);
    }

    private String sandboxMode(HarnessStage stage) {
        if (stage == null) {
            throw new IllegalStateException("runtime stage is required");
        }
        String configured = stage == HarnessStage.IMPLEMENTATION
                ? runtimeProperties.getImplementationSandboxMode()
                : runtimeProperties.getSandboxMode();
        String required = stage == HarnessStage.IMPLEMENTATION
                ? "workspace-write" : "read-only";
        if (!required.equals(configured)) {
            throw new IllegalStateException(
                    "runtime sandbox configuration exceeds or misses stage boundary");
        }
        return required;
    }

    private RuntimeVersion probeRuntimeVersion(Path root) {
        long timeoutSeconds = runtimeProperties.getVersionProbeTimeoutSeconds();
        long maximumBytes = runtimeProperties.getVersionProbeMaxBytes();
        if (timeoutSeconds < 1L || maximumBytes < 1L) {
            throw new IllegalStateException("runtime version probe bounds must be positive");
        }
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile(root, "codex-version-", ".txt");
            secureFile(output);
            ProcessBuilder builder = new ProcessBuilder(
                    runtimeProperties.getCodexCommand(), "--version");
            builder.directory(root.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            applyBaseEnvironment(builder, root);
            process = builder.start();
            awaitVersionProbe(process, output, timeoutSeconds, maximumBytes);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Codex version probe failed");
            }
            if (Files.size(output) > maximumBytes) {
                throw new IllegalStateException("Codex version probe output limit exceeded");
            }
            String raw = new String(Files.readAllBytes(output), StandardCharsets.UTF_8).trim();
            Matcher matcher = CODEX_VERSION_PATTERN.matcher(raw);
            if (!matcher.matches()) {
                throw new IllegalStateException("Codex version output is not recognized");
            }
            String semanticVersion = matcher.group(1);
            Set<String> supported = runtimeProperties.getSupportedCodexVersions();
            if (supported == null || !supported.contains(semanticVersion)) {
                throw new IllegalStateException(
                        "Codex version is not in the verified compatibility set");
            }
            return new RuntimeVersion("codex-cli " + semanticVersion);
        } catch (IOException ex) {
            throw new IllegalStateException("Codex version probe could not start", ex);
        } finally {
            if (process != null && process.isAlive()) {
                destroyProcessTree(process);
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // Probe 临时文件清理失败由 runtime root 运维检查发现，不泄漏输出正文。
                }
            }
        }
    }

    private void awaitVersionProbe(Process process, Path output, long timeoutSeconds,
                                   long maximumBytes) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (process.isAlive()) {
                if (Files.size(output) > maximumBytes) {
                    destroyProcessTree(process);
                    process.waitFor(1L, TimeUnit.SECONDS);
                    throw new IllegalStateException("Codex version probe output limit exceeded");
                }
                if (System.nanoTime() >= deadline) {
                    destroyProcessTree(process);
                    process.waitFor(1L, TimeUnit.SECONDS);
                    throw new IllegalStateException("Codex version probe timed out");
                }
                Thread.sleep(10L);
            }
        } catch (IOException ex) {
            destroyProcessTree(process);
            throw new IllegalStateException("Codex version probe output could not be inspected", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process);
            throw new IllegalStateException("Codex version probe was interrupted", ex);
        }
    }

    private WorkspaceInspection inspectWorkspace(String workingDir) {
        if (workingDir == null || workingDir.trim().isEmpty()) {
            throw new IllegalStateException("runtime working directory is unavailable");
        }
        try {
            Path workspace = Paths.get(workingDir).toRealPath();
            if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("runtime working directory is unavailable");
            }
            WorkspaceBoundary boundary = workspaceBoundary(workspace);
            requireProjectConfigAbsent(workspace, boundary.getRoot());
            List<WorkspaceRepoSkill> skills = repoSkills(boundary.getRoot());
            WorkspaceRuntimeInventory inventory = new WorkspaceRuntimeInventory(
                    boundary.getKind(), true, skills);
            return new WorkspaceInspection(boundary.getRoot(), inventory);
        } catch (IOException ex) {
            throw new IllegalStateException("runtime workspace could not be inspected", ex);
        }
    }

    private WorkspaceBoundary workspaceBoundary(Path workspace) {
        Path cursor = workspace;
        while (cursor != null) {
            if (Files.exists(cursor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return new WorkspaceBoundary(cursor, WorkspaceBoundaryKind.GIT_ROOT);
            }
            cursor = cursor.getParent();
        }
        return new WorkspaceBoundary(workspace, WorkspaceBoundaryKind.APPROVED_ROOT);
    }

    private void requireProjectConfigAbsent(Path workspace, Path boundary) {
        Path cursor = workspace;
        while (cursor != null) {
            Path configuration = cursor.resolve(".codex").resolve("config.toml");
            if (Files.exists(configuration, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(
                        "project .codex/config.toml is not allowed for Harness M3");
            }
            if (cursor.equals(boundary)) {
                return;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("runtime workspace boundary is inconsistent");
    }

    private List<WorkspaceRepoSkill> repoSkills(Path boundary) throws IOException {
        Path skillRoot = boundary.resolve(".agents").resolve("skills");
        if (!Files.exists(skillRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Collections.emptyList();
        }
        Path realSkillRoot = skillRoot.toRealPath();
        requireInsideBoundary(realSkillRoot, boundary, "repository Skill root");
        if (!Files.isDirectory(realSkillRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("repository Skill root is not a directory");
        }
        List<Path> candidates = new ArrayList<Path>();
        try (Stream<Path> children = Files.list(skillRoot)) {
            children.sorted().forEach(candidates::add);
        }
        List<WorkspaceRepoSkill> skills = new ArrayList<WorkspaceRepoSkill>();
        for (Path candidate : candidates) {
            String id = candidate.getFileName().toString();
            requireIdentifier(id, "repository Skill id");
            Path realCandidate = candidate.toRealPath();
            requireInsideBoundary(realCandidate, boundary, "repository Skill directory");
            if (!Files.isDirectory(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path entry = candidate.resolve("SKILL.md");
            if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path realEntry = entry.toRealPath();
            requireInsideBoundary(realEntry, boundary, "repository Skill entry");
            if (!Files.isRegularFile(realEntry, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("repository Skill entry is not a regular file");
            }
            String relative = boundary.relativize(entry.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
            skills.add(new WorkspaceRepoSkill(id, relative,
                    com.example.agentweb.domain.harness.HarnessHashing.sha256(
                            Files.readAllBytes(realEntry))));
        }
        return skills;
    }

    private void requireInsideBoundary(Path path, Path boundary, String name) {
        if (!path.startsWith(boundary)) {
            throw new IllegalStateException(name + " escapes the repository boundary");
        }
    }

    private void requireUnchangedPreflight(AgentExecutionSpec spec,
                                           RuntimePreflightReport current) {
        if (!spec.getEnforcementProfile().getProfileHash().equals(
                current.getEnforcementProfile().getProfileHash())) {
            throw new IllegalStateException("runtime enforcement changed after snapshot");
        }
        if (!spec.getWorkspaceInventory().getInventoryHash().equals(
                current.getWorkspaceInventory().getInventoryHash())) {
            throw new IllegalStateException("workspace runtime inventory changed after snapshot");
        }
    }

    private String eventType(String line) {
        try {
            JsonNode type = MAPPER.readTree(line).get("type");
            if (type != null && type.isTextual()
                    && type.asText().matches("[A-Za-z0-9_.-]{1,80}")) {
                return type.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 输出仍进入脱敏 Evidence，只使用固定摘要写 SQLite。
        }
        return null;
    }

    private String outputSummary(String type) {
        return type == null ? "codex output received" : "codex event received: " + type;
    }

    private boolean exceeded(long sinceNanos, long nowNanos, long seconds) {
        return seconds > 0L && nowNanos - sinceNanos >= TimeUnit.SECONDS.toNanos(seconds);
    }

    private int waitForExit(Process process) throws InterruptedException {
        if (!process.waitFor(1L, TimeUnit.SECONDS)) {
            destroyProcessTree(process);
            process.waitFor(1L, TimeUnit.SECONDS);
        }
        return process.isAlive() ? -1 : process.exitValue();
    }

    private String redactSecrets(String value, List<String> secrets) {
        String redacted = value;
        for (String secret : secrets) {
            redacted = redacted.replace(secret, "[REDACTED]");
        }
        return redacted;
    }

    private String boundedEvidenceLine(String value) {
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }

    private void observeCompletedCommand(String redactedLine, ActiveExecution active) {
        try {
            JsonNode root = MAPPER.readTree(redactedLine);
            JsonNode item = root == null ? null : root.path("item");
            JsonNode exitCode = item == null ? null : item.get("exit_code");
            if (!"item.completed".equals(root.path("type").asText())
                    || !"command_execution".equals(item.path("type").asText())
                    || !item.path("command").isTextual()
                    || item.path("command").asText().trim().isEmpty()
                    || exitCode == null || !exitCode.canConvertToInt()) {
                return;
            }
            active.observeCommand(item.path("command").asText(), exitCode.asInt(),
                    com.example.agentweb.domain.harness.HarnessHashing.sha256(
                            item.path("aggregated_output").asText("")));
        } catch (RuntimeException | IOException ignored) {
            // 非法或非命令 JSON 仍保留为脱敏 Runtime Evidence，不进入命令证据。
        }
    }

    private String array(List<String> values) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                result.append(", ");
            }
            result.append(quoted(values.get(index)));
        }
        return result.append(']').toString();
    }

    private String quoted(String value) {
        if (value.indexOf(NEWLINE) >= 0 || value.indexOf(CARRIAGE_RETURN) >= 0
                || value.indexOf(NULL_CHARACTER) >= 0) {
            throw new IllegalStateException("MCP configuration contains control characters");
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private void requireIdentifier(String value, String name) {
        if (value == null || !value.matches(SAFE_IDENTIFIER_PATTERN)) {
            throw new IllegalStateException(name + " is unsafe");
        }
    }

    private void requireEnvironmentVariable(String value) {
        if (value == null || !value.matches(SAFE_ENVIRONMENT_VARIABLE_PATTERN)) {
            throw new IllegalStateException("MCP environment variable is unsafe");
        }
    }

    private Path runtimeRoot() {
        return Paths.get(runtimeProperties.getTempRoot()).toAbsolutePath().normalize();
    }

    private boolean cleanup(Path root) {
        if (root == null || Files.notExists(root)) {
            return true;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new CleanupException(ex);
                }
            });
            return true;
        } catch (IOException | UncheckedIOException | CleanupException ex) {
            return false;
        }
    }

    private void secureDirectory(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL 由本机服务账户边界承担；POSIX 环境严格设置 700。
        }
    }

    private void secureFile(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL 由本机服务账户边界承担；POSIX 环境严格设置 600。
        }
    }

    private boolean supportsProcessTreeCancellation() {
        try {
            Class.forName("java.lang.ProcessHandle");
            Process.class.getMethod("toHandle");
            return true;
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    private String runtimeHandle(Process process) {
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object handle = Process.class.getMethod("toHandle").invoke(process);
            Object pid = handleType.getMethod("pid").invoke(handle);
            return "pid:" + pid;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("runtime process handle is unavailable", ex);
        }
    }

    private void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object rootHandle = Process.class.getMethod("toHandle").invoke(process);
            @SuppressWarnings("unchecked")
            Stream<Object> descendants = (Stream<Object>) handleType.getMethod("descendants")
                    .invoke(rootHandle);
            List<Object> handles = new ArrayList<Object>();
            try {
                descendants.forEach(handles::add);
            } finally {
                descendants.close();
            }
            Collections.reverse(handles);
            for (Object handle : handles) {
                handleType.getMethod("destroyForcibly").invoke(handle);
            }
            handleType.getMethod("destroyForcibly").invoke(rootHandle);
        } catch (Exception ex) {
            process.destroyForcibly();
        }
    }

    private static final class RuntimeVersion {

        private final String displayVersion;

        private RuntimeVersion(String displayVersion) {
            this.displayVersion = displayVersion;
        }

        private String getDisplayVersion() {
            return displayVersion;
        }
    }

    private static final class WorkspaceBoundary {

        private final Path root;
        private final WorkspaceBoundaryKind kind;

        private WorkspaceBoundary(Path root, WorkspaceBoundaryKind kind) {
            this.root = root;
            this.kind = kind;
        }

        private Path getRoot() {
            return root;
        }

        private WorkspaceBoundaryKind getKind() {
            return kind;
        }
    }

    private static final class WorkspaceInspection {

        private final Path boundaryRoot;
        private final WorkspaceRuntimeInventory inventory;

        private WorkspaceInspection(Path boundaryRoot, WorkspaceRuntimeInventory inventory) {
            this.boundaryRoot = boundaryRoot;
            this.inventory = inventory;
        }

        private Path getBoundaryRoot() {
            return boundaryRoot;
        }

        private WorkspaceRuntimeInventory getInventory() {
            return inventory;
        }
    }

    private static final class PreflightInspection {

        private final RuntimePreflightReport report;
        private final WorkspaceInspection workspace;

        private PreflightInspection(RuntimePreflightReport report,
                                    WorkspaceInspection workspace) {
            this.report = report;
            this.workspace = workspace;
        }

        private RuntimePreflightReport getReport() {
            return report;
        }

        private WorkspaceInspection getWorkspace() {
            return workspace;
        }
    }

    private enum TerminalKind {
        /** 成功。 */
        SUCCEEDED,
        /** 失败。 */
        FAILED,
        /** 超时。 */
        TIMED_OUT,
        /** 取消。 */
        CANCELLED
    }

    private static final class ActiveExecution {

        private final AgentExecutionSpec spec;
        private final RuntimeEventSink eventSink;
        private final Process process;
        private final Path executionRoot;
        private final List<String> secrets;
        private final Path outputLastMessage;
        private final String runtimeHandle;
        private final long startedNanos;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicBoolean terminalEmitted = new AtomicBoolean();
        private final ByteArrayOutputStream evidence = new ByteArrayOutputStream();
        private final List<RuntimeCommandObservation> commandObservations =
                new ArrayList<RuntimeCommandObservation>();

        private ActiveExecution(AgentExecutionSpec spec, RuntimeEventSink eventSink,
                                Process process, Path executionRoot, List<String> secrets,
                                Path outputLastMessage, String runtimeHandle, long startedNanos) {
            this.spec = spec;
            this.eventSink = eventSink;
            this.process = process;
            this.executionRoot = executionRoot;
            this.secrets = Collections.unmodifiableList(new ArrayList<String>(secrets));
            this.outputLastMessage = outputLastMessage;
            this.runtimeHandle = runtimeHandle;
            this.startedNanos = startedNanos;
        }

        private void emitStarted(Instant now) {
            long next = sequence.incrementAndGet();
            eventSink.onEvent(new RuntimeEvent(spec.getExecutionId(),
                    RuntimeExecutionSignal.started(next,
                            spec.getEnforcementProfile().getRuntimeVersion(),
                            runtimeHandle, now), "runtime started"));
        }

        private void emitOutput(String redactedLine, String summary, long maximumBytes, Instant now) {
            appendEvidence(redactedLine, maximumBytes);
            long next = sequence.incrementAndGet();
            eventSink.onEvent(new RuntimeEvent(spec.getExecutionId(),
                    RuntimeExecutionSignal.output(next, null, now), summary));
        }

        private void emitTerminal(TerminalKind terminal, Integer exitCode, String reason,
                                  String evidenceReference, boolean cleaned,
                                  RuntimeArtifactBundle artifactBundle, Instant now) {
            if (terminalEmitted.get()) {
                return;
            }
            long next = sequence.incrementAndGet();
            RuntimeExecutionSignal signal;
            switch (terminal) {
                case SUCCEEDED:
                    signal = RuntimeExecutionSignal.succeeded(next,
                            exitCode == null ? 0 : exitCode.intValue(),
                            evidenceReference, cleaned, now);
                    break;
                case TIMED_OUT:
                    signal = RuntimeExecutionSignal.timedOut(next, reason,
                            evidenceReference, cleaned, now);
                    break;
                case CANCELLED:
                    signal = RuntimeExecutionSignal.cancelled(next, exitCode, reason,
                            evidenceReference, cleaned, now);
                    break;
                case FAILED:
                default:
                    signal = RuntimeExecutionSignal.failed(next, exitCode, reason,
                            evidenceReference, cleaned, now);
                    break;
            }
            eventSink.onEvent(new RuntimeEvent(spec.getExecutionId(), signal, reason,
                    artifactBundle, commandObservations()));
            terminalEmitted.set(true);
        }

        private void observeCommand(String command, int exitCode, String outputHash) {
            commandObservations.add(new RuntimeCommandObservation(
                    commandObservations.size() + 1, command, exitCode, outputHash));
        }

        private List<RuntimeCommandObservation> commandObservations() {
            return new ArrayList<RuntimeCommandObservation>(commandObservations);
        }

        private void requestCancellation() {
            cancellationRequested.set(true);
        }

        private boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        private AgentExecutionSpec getSpec() {
            return spec;
        }

        private Process getProcess() {
            return process;
        }

        private Path getExecutionRoot() {
            return executionRoot;
        }

        private Path getOutputLastMessage() {
            return outputLastMessage;
        }

        private List<String> getSecrets() {
            return secrets;
        }

        private long getStartedNanos() {
            return startedNanos;
        }

        private synchronized void appendEvidence(String line, long maximumBytes) {
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            long remaining = maximumBytes - evidence.size();
            if (remaining <= 0L) {
                return;
            }
            int length = (int) Math.min((long) bytes.length, remaining);
            evidence.write(bytes, 0, length);
        }

        private synchronized byte[] evidenceBytes() {
            return evidence.toByteArray();
        }
    }

    private static final class OutputState {

        private long bytes;
        private boolean turnFailed;

        private void addBytes(long value) {
            bytes += value;
        }

        private long getBytes() {
            return bytes;
        }

        private void markTurnFailed() {
            turnFailed = true;
        }

        private boolean isTurnFailed() {
            return turnFailed;
        }
    }

    private static final class TerminalOutcome {

        private final TerminalKind kind;
        private final String reason;
        private final Integer exitCode;

        private TerminalOutcome(TerminalKind kind, String reason, Integer exitCode) {
            this.kind = kind;
            this.reason = reason;
            this.exitCode = exitCode;
        }

        private static TerminalOutcome succeeded(int exitCode) {
            return new TerminalOutcome(TerminalKind.SUCCEEDED, "runtime succeeded",
                    Integer.valueOf(exitCode));
        }

        private static TerminalOutcome failed(String reason, Integer exitCode) {
            return new TerminalOutcome(TerminalKind.FAILED, reason, exitCode);
        }

        private static TerminalOutcome timedOut(String reason) {
            return new TerminalOutcome(TerminalKind.TIMED_OUT, reason, null);
        }

        private static TerminalOutcome cancelled(String reason, Integer exitCode) {
            return new TerminalOutcome(TerminalKind.CANCELLED, reason, exitCode);
        }

        private TerminalOutcome withExitCode(int value) {
            return new TerminalOutcome(kind, reason, Integer.valueOf(value));
        }

        private TerminalOutcome evidencePersistenceFailed() {
            TerminalKind failedKind = kind == TerminalKind.SUCCEEDED ? TerminalKind.FAILED : kind;
            return new TerminalOutcome(failedKind,
                    "runtime evidence persistence failed", exitCode);
        }

        private TerminalKind getKind() {
            return kind;
        }

        private String getReason() {
            return reason;
        }

        private Integer getExitCode() {
            return exitCode;
        }
    }

    private static final class CleanupException extends RuntimeException {

        private CleanupException(Throwable cause) {
            super(cause);
        }
    }
}
