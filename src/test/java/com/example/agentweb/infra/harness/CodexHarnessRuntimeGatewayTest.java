package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.port.AgentExecutionSpec;
import com.example.agentweb.app.harness.port.AgentRuntimeStartException;
import com.example.agentweb.app.harness.port.RuntimeEvent;
import com.example.agentweb.app.harness.port.RuntimePreflightReport;
import com.example.agentweb.config.harness.HarnessRuntimeProperties;
import com.example.agentweb.config.harness.HarnessSecurityProperties;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilityAccess;
import com.example.agentweb.domain.harness.HarnessHashing;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.McpCapability;
import com.example.agentweb.domain.harness.McpCapabilityType;
import com.example.agentweb.domain.harness.McpSecretReference;
import com.example.agentweb.domain.harness.McpServerDefinition;
import com.example.agentweb.domain.harness.RuntimeEnforcementProfile;
import com.example.agentweb.domain.harness.RuntimeExecutionSignalType;
import com.example.agentweb.domain.harness.SelectedMcpServer;
import com.example.agentweb.domain.harness.WorkspaceBoundaryKind;
import com.example.agentweb.domain.harness.WorkspaceRuntimeInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskExecutor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness Codex Adapter 的 Stub/Fake MCP、Secret 脱敏、终止与清理测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class CodexHarnessRuntimeGatewayTest {

    private static final String SECRET = "secret-value-never-persist";
    private static final String PROVIDER_SECRET = "provider-secret-never-persist";

    @TempDir
    Path tempDir;

    private CodexHarnessRuntimeGateway gateway;
    private HarnessRuntimeProperties gatewayProperties;
    private HarnessSecurityProperties gatewaySecurityProperties;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
    }

    @Test
    void shouldRunWithIsolatedConfigAuthorizedMcpAndRedactedSecretThenClean() throws Exception {
        Path arguments = tempDir.resolve("codex-arguments.txt");
        Path stub = script("success.sh", "#!/bin/sh\n"
                + "test \"$HOME\" = \"$CODEX_HOME\" || exit 21\n"
                + "test \"$READER_API_KEY\" = \"" + SECRET + "\" || exit 22\n"
                + "test ! -e \"$CODEX_HOME/config.toml\" || exit 23\n"
                + "printf '%s\\n' \"$@\" > '" + arguments + "'\n"
                + "cat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"thread.started\",\"thread_id\":\"t-1\"}'\n"
                + "printf '%s\\n' '{\"type\":\"item.completed\",\"item\":{"
                + "\"type\":\"command_execution\",\"command\":\"mvn focused\","
                + "\"aggregated_output\":\"" + SECRET + " output\","
                + "\"exit_code\":0,\"status\":\"completed\"}}'\n"
                + "printf '%s\\n' '{\"type\":\"item.completed\",\"secret\":\"" + SECRET + "\"}'\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        gateway = gateway(stub, 5L);
        Events events = new Events();

        gateway.start(spec("exec-success", Collections.singletonList(reader())), events);
        events.awaitTerminal();

        assertEquals(RuntimeExecutionSignalType.SUCCEEDED, events.terminal().getSignal().getType());
        assertNotNull(events.terminal().getArtifactBundle());
        assertEquals(HarnessStage.ANALYSIS, events.terminal().getArtifactBundle().getStage());
        assertEquals(4, events.terminal().getArtifactBundle().getArtifacts().size());
        assertEquals(1, events.terminal().getCommandObservations().size());
        assertEquals("mvn focused",
                events.terminal().getCommandObservations().get(0).getCommand());
        assertEquals(0, events.terminal().getCommandObservations().get(0).getExitCode());
        assertEquals(HarnessHashing.sha256("[REDACTED] output"),
                events.terminal().getCommandObservations().get(0).getOutputHash());
        assertTrue(events.events.stream().anyMatch(event ->
                event.getSignal().getType() == RuntimeExecutionSignalType.OUTPUT));
        assertTrue(events.events.stream().noneMatch(event ->
                event.getSummary() != null && event.getSummary().contains(SECRET)));
        assertTrue(events.terminal().getSignal().getEvidenceReference().startsWith(
                "artifact:runtime-jsonl-exec-success:1:"));
        assertTrue(events.started().getSignal().getRuntimeHandle().matches("pid:[0-9]+"));
        List<String> actualArguments = Files.readAllLines(arguments, StandardCharsets.UTF_8);
        assertTrue(actualArguments.contains("--output-schema"));
        assertTrue(actualArguments.contains("--output-last-message"));
        assertOverride(actualArguments, "mcp_servers.reader.command=\"fake-mcp\"");
        assertOverride(actualArguments, "mcp_servers.reader.args=[\"--stdio\"]");
        assertOverride(actualArguments, "mcp_servers.reader.env_vars=[\"READER_API_KEY\"]");
        assertOverride(actualArguments, "mcp_servers.reader.required=true");
        assertOverride(actualArguments, "mcp_servers.reader.startup_timeout_sec=10");
        assertOverride(actualArguments, "mcp_servers.reader.tool_timeout_sec=30");
        assertOverride(actualArguments, "mcp_servers.reader.enabled_tools=[\"search\"]");
        assertOverride(actualArguments, "mcp_servers.reader.disabled_tools=[\"update\"]");
        assertOverride(actualArguments,
                "mcp_servers.reader.default_tools_approval_mode=\"writes\"");
        String evidence = evidenceText("exec-success");
        assertFalse(evidence.contains(SECRET));
        assertTrue(evidence.contains("[REDACTED]"));
        assertRuntimeRootEmpty();
    }

    @Test
    void explicitlyConfiguredProviderCredentialShouldBeInjectedAndRedacted() throws Exception {
        Path stub = script("provider-credential.sh", "#!/bin/sh\n"
                + "test \"$OPENAI_API_KEY\" = \"" + PROVIDER_SECRET + "\" || exit 31\n"
                + "cat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"item.completed\",\"credential\":\""
                + PROVIDER_SECRET + "\"}'\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        gateway = gateway(stub, 5L);
        gatewayProperties.setProviderCredentialReference("CODEX_PROVIDER_CREDENTIAL");
        Events events = new Events();

        gateway.start(spec("exec-provider-credential", Collections.emptyList()), events);
        events.awaitTerminal();

        assertEquals(RuntimeExecutionSignalType.SUCCEEDED, events.terminal().getSignal().getType());
        assertTrue(events.events.stream().noneMatch(event ->
                event.getSummary() != null && event.getSummary().contains(PROVIDER_SECRET)));
        String evidence = evidenceText("exec-provider-credential");
        assertFalse(evidence.contains(PROVIDER_SECRET));
        assertTrue(evidence.contains("[REDACTED]"));
    }

    @Test
    void providerCredentialEnvironmentVariableShouldNotBeGenerallyInherited() throws Exception {
        Path stub = script("provider-inheritance.sh", "#!/bin/sh\nexit 0\n");
        gateway = gateway(stub, 5L);
        gatewaySecurityProperties.setInheritedEnvironmentVariables(
                new java.util.LinkedHashSet<String>(Arrays.asList("PATH", "OPENAI_API_KEY")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> gateway.preflight(AgentRuntime.CODEX, tempDir.toString()));

        assertTrue(error.getMessage().contains("provider credential"));
    }

    @Test
    void malformedFinalArtifactBundleShouldFailClosed() throws Exception {
        Path stub = script("malformed-bundle.sh", "#!/bin/sh\n"
                + "previous=''\n"
                + "for argument in \"$@\"; do\n"
                + "  if [ \"$previous\" = \"--output-last-message\" ]; then\n"
                + "    printf '%s\\n' 'not-json' > \"$argument\"\n"
                + "  fi\n"
                + "  previous=\"$argument\"\n"
                + "done\n"
                + "cat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        gateway = gateway(stub, 5L);
        Events events = new Events();

        gateway.start(spec("exec-malformed-bundle", Collections.emptyList()), events);
        events.awaitTerminal();

        assertEquals(RuntimeExecutionSignalType.FAILED, events.terminal().getSignal().getType());
        assertTrue(events.terminal().getSummary().contains("artifact bundle"));
    }

    @Test
    void turnFailedReadDuringFinalDrainShouldFailEvenWhenProcessExitsZero() throws Exception {
        Path stub = script("turn-failed-zero.sh", "#!/bin/sh\ncat >/dev/null\n"
                + "printf '%s\\n' '{ \"type\" : \"turn.failed\" }'\nexit 0\n");
        gateway = gateway(stub, 5L);
        Events events = new Events();

        gateway.start(spec("exec-turn-failed", Collections.emptyList()), events);
        events.awaitTerminal();

        assertEquals(RuntimeExecutionSignalType.FAILED, events.terminal().getSignal().getType());
        assertTrue(events.terminal().getSignal().getEvidenceReference().startsWith(
                "artifact:runtime-jsonl-exec-turn-failed:1:"));
    }

    @Test
    void outputLimitAndIdleTimeoutShouldFailClosedAndPersistEvidence() throws Exception {
        Path noisy = script("noisy.sh", "#!/bin/sh\ncat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"item.completed\",\"payload\":\"1234567890\"}'\n");
        gateway = gateway(noisy, 30L, 30L, 16L);
        Events limited = new Events();
        gateway.start(spec("exec-output-limit", Collections.emptyList()), limited);
        limited.awaitTerminal();
        assertEquals(RuntimeExecutionSignalType.FAILED, limited.terminal().getSignal().getType());
        assertTrue(limited.terminal().getSummary().contains("output limit"));
        assertTrue(limited.terminal().getSignal().getEvidenceReference().startsWith("artifact:"));
        gateway.close();

        Path idle = script("idle.sh", "#!/bin/sh\nsleep 20\n");
        gateway = gateway(idle, 30L, 1L, 1024L);
        Events timedOut = new Events();
        gateway.start(spec("exec-idle", Collections.emptyList()), timedOut);
        timedOut.awaitTerminal();
        assertEquals(RuntimeExecutionSignalType.TIMED_OUT,
                timedOut.terminal().getSignal().getType());
        assertTrue(timedOut.terminal().getSummary().contains("idle timeout"));
    }

    @Test
    void emptyMcpSelectionShouldNotWriteAnyMcpServerConfiguration() throws Exception {
        Path arguments = tempDir.resolve("no-mcp-arguments.txt");
        Path stub = script("no-mcp.sh", "#!/bin/sh\n"
                + "test ! -e \"$CODEX_HOME/config.toml\" || exit 41\n"
                + "printf '%s\\n' \"$@\" > '" + arguments + "'\n"
                + "cat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        gateway = gateway(stub, 5L);
        Events events = new Events();

        gateway.start(spec("exec-no-mcp", Collections.emptyList()), events);
        events.awaitTerminal();

        assertEquals(RuntimeExecutionSignalType.SUCCEEDED, events.terminal().getSignal().getType());
        assertTrue(Files.readAllLines(arguments, StandardCharsets.UTF_8).stream()
                .noneMatch(argument -> argument.startsWith("mcp_servers.")));
    }

    @Test
    void failureTimeoutAndCancellationShouldEachEmitTerminalAndClean() throws Exception {
        Path failure = script("failure.sh", "#!/bin/sh\ncat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"turn.failed\"}'\nexit 7\n");
        gateway = gateway(failure, 5L);
        Events failed = new Events();
        gateway.start(spec("exec-failed", Collections.emptyList()), failed);
        failed.awaitTerminal();
        assertEquals(RuntimeExecutionSignalType.FAILED, failed.terminal().getSignal().getType());
        assertRuntimeRootEmpty();
        gateway.close();

        Path slow = script("slow.sh", "#!/bin/sh\nsleep 20\n");
        gateway = gateway(slow, 1L);
        Events timedOut = new Events();
        gateway.start(spec("exec-timeout", Collections.emptyList()), timedOut);
        timedOut.awaitTerminal();
        assertEquals(RuntimeExecutionSignalType.TIMED_OUT,
                timedOut.terminal().getSignal().getType());
        assertRuntimeRootEmpty();
        gateway.close();

        gateway = gateway(slow, 30L);
        Events cancelled = new Events();
        gateway.start(spec("exec-cancel", Collections.emptyList()), cancelled);
        gateway.cancel("exec-cancel");
        cancelled.awaitTerminal();
        assertEquals(RuntimeExecutionSignalType.CANCELLED,
                cancelled.terminal().getSignal().getType());
        assertRuntimeRootEmpty();
    }

    @Test
    void spawnFailureShouldNotLeaveTemporaryConfig() throws Exception {
        Path counter = tempDir.resolve("probe-count.txt");
        Path disappearing = tempDir.resolve("disappearing-codex.sh");
        rawScript(disappearing.getFileName().toString(), "#!/bin/sh\n"
                + "if [ \"${1:-}\" = \"--version\" ]; then\n"
                + "  count=$(cat '" + counter + "' 2>/dev/null || printf '0')\n"
                + "  count=$((count + 1))\n"
                + "  printf '%s' \"$count\" > '" + counter + "'\n"
                + "  printf '%s\\n' 'codex-cli 0.145.0'\n"
                + "  if [ \"$count\" -eq 2 ]; then rm -- \"$0\"; fi\n"
                + "  exit 0\n"
                + "fi\n"
                + "exit 96\n");
        gateway = gateway(disappearing, 5L);
        AgentExecutionSpec executionSpec = spec("exec-spawn-failure", Collections.emptyList());

        AgentRuntimeStartException error = assertThrows(AgentRuntimeStartException.class,
                () -> gateway.start(executionSpec, event -> { }));

        assertTrue(error.isTemporaryConfigCleaned());
        assertRuntimeRootEmpty();
    }

    @Test
    void cleanupFailureShouldBeReportedExplicitly() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Path stub = script("cleanup-failure.sh", "#!/bin/sh\ncat >/dev/null\n"
                + "mkdir \"$CODEX_HOME/blocked\"\n"
                + "printf '%s' 'blocked' > \"$CODEX_HOME/blocked/evidence\"\n"
                + "chmod 000 \"$CODEX_HOME/blocked\"\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        gateway = gateway(stub, 5L);
        Events events = new Events();

        gateway.start(spec("exec-cleanup-failure", Collections.emptyList()), events);
        events.awaitTerminal();

        assertEquals(RuntimeExecutionSignalType.SUCCEEDED, events.terminal().getSignal().getType());
        assertFalse(events.terminal().getSignal().getTemporaryConfigCleaned().booleanValue());
        Path executionRoot;
        try (java.util.stream.Stream<Path> children = Files.list(tempDir.resolve("runtime"))) {
            executionRoot = children.findFirst().orElseThrow(AssertionError::new);
        }
        Files.setPosixFilePermissions(executionRoot.resolve("blocked"),
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void eventSinkFailureShouldStillTerminateProcessAndCleanTemporaryConfig() throws Exception {
        Path stub = script("sink-failure.sh", "#!/bin/sh\ncat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        gateway = gateway(stub, 5L);
        CountDownLatch terminalLatch = new CountDownLatch(1);
        AtomicReference<RuntimeEvent> terminal = new AtomicReference<RuntimeEvent>();

        gateway.start(spec("exec-sink-failure", Collections.emptyList()), event -> {
            if (event.getSignal().getType() == RuntimeExecutionSignalType.OUTPUT) {
                throw new IllegalStateException("simulated database callback failure");
            }
            if (event.getSignal().getType() == RuntimeExecutionSignalType.FAILED) {
                terminal.set(event);
                terminalLatch.countDown();
            }
        });

        assertTrue(terminalLatch.await(8L, TimeUnit.SECONDS));
        assertEquals(RuntimeExecutionSignalType.FAILED, terminal.get().getSignal().getType());
        assertRuntimeRootEmpty();
    }

    @Test
    void preflightShouldCaptureCompatibleVersionAndRepositorySkillInventory() throws Exception {
        Path stub = script("preflight-success.sh", "#!/bin/sh\nexit 19\n");
        Path repository = Files.createDirectories(tempDir.resolve("repository"));
        Files.createDirectory(repository.resolve(".git"));
        Path workingDir = Files.createDirectories(repository.resolve("module"));
        Path skillEntry = Files.createDirectories(repository.resolve(
                ".agents/skills/java-tdd")).resolve("SKILL.md");
        Files.write(skillEntry, "# java-tdd\n".getBytes(StandardCharsets.UTF_8));
        gateway = gateway(stub, 5L);

        RuntimePreflightReport report = gateway.preflight(AgentRuntime.CODEX,
                workingDir.toString());

        assertEquals("codex-cli 0.145.0",
                report.getEnforcementProfile().getRuntimeVersion());
        assertEquals("codex-harness-adapter@1",
                report.getEnforcementProfile().getAdapterVersion());
        assertEquals("m0-2026-07-22",
                report.getEnforcementProfile().getCompatibilityMatrixVersion());
        assertTrue(report.getEnforcementProfile().supportsMcpToolIsolation());
        WorkspaceRuntimeInventory inventory = report.getWorkspaceInventory();
        assertEquals(WorkspaceBoundaryKind.GIT_ROOT, inventory.getBoundaryKind());
        assertTrue(inventory.isProjectConfigAbsent());
        assertEquals(1, inventory.getRepoSkills().size());
        assertEquals("java-tdd", inventory.getRepoSkills().get(0).getId());
        assertEquals(".agents/skills/java-tdd/SKILL.md",
                inventory.getRepoSkills().get(0).getRelativeEntryPath());
        assertEquals(HarnessHashing.sha256("# java-tdd\n"),
                inventory.getRepoSkills().get(0).getEntryHash());
    }

    @Test
    void preflightShouldFailClosedForUnsupportedOrMalformedRuntimeVersion() throws Exception {
        Path unsupported = rawScript("unsupported-version.sh", "#!/bin/sh\n"
                + "test \"${1:-}\" = \"--version\" || exit 91\n"
                + "printf '%s\\n' 'codex-cli 0.146.0'\n");
        gateway = gateway(unsupported, 5L);

        assertThrows(IllegalStateException.class,
                () -> gateway.preflight(AgentRuntime.CODEX, tempDir.toString()));
        gateway.close();

        Path malformed = rawScript("malformed-version.sh", "#!/bin/sh\n"
                + "test \"${1:-}\" = \"--version\" || exit 92\n"
                + "printf '%s\\n' 'unknown codex build'\n");
        gateway = gateway(malformed, 5L);

        assertThrows(IllegalStateException.class,
                () -> gateway.preflight(AgentRuntime.CODEX, tempDir.toString()));
    }

    @Test
    void preflightVersionProbeShouldBeBoundedByTimeoutAndOutputSize() throws Exception {
        Path timeout = rawScript("version-timeout.sh", "#!/bin/sh\n"
                + "test \"${1:-}\" = \"--version\" || exit 93\n"
                + "sleep 20\n");
        gateway = gateway(timeout, 5L);
        gatewayProperties().setVersionProbeTimeoutSeconds(1L);

        assertThrows(IllegalStateException.class,
                () -> gateway.preflight(AgentRuntime.CODEX, tempDir.toString()));
        gateway.close();

        String oversizedOutput = String.join("", Collections.nCopies(5000, "x"));
        Path oversized = rawScript("version-oversized.sh", "#!/bin/sh\n"
                + "test \"${1:-}\" = \"--version\" || exit 94\n"
                + "printf '%s\\n' '" + oversizedOutput + "'\n");
        gateway = gateway(oversized, 5L);
        gatewayProperties().setVersionProbeMaxBytes(4096L);

        assertThrows(IllegalStateException.class,
                () -> gateway.preflight(AgentRuntime.CODEX, tempDir.toString()));
    }

    @Test
    void preflightShouldBlockAncestorProjectConfigAndRepoSkillSymlinkEscape() throws Exception {
        Path stub = script("workspace-safety.sh", "#!/bin/sh\nexit 95\n");
        Path repository = Files.createDirectories(tempDir.resolve("unsafe-repository"));
        Files.createDirectory(repository.resolve(".git"));
        Path workingDir = Files.createDirectories(repository.resolve("module"));
        Path projectConfig = Files.createDirectories(repository.resolve(".codex"))
                .resolve("config.toml");
        Files.write(projectConfig, "model = \"unexpected\"\n".getBytes(StandardCharsets.UTF_8));
        gateway = gateway(stub, 5L);

        assertThrows(IllegalStateException.class,
                () -> gateway.preflight(AgentRuntime.CODEX, workingDir.toString()));
        Files.delete(projectConfig);
        gateway.close();

        Path external = tempDir.resolve("external-skill.md");
        Files.write(external, "# external\n".getBytes(StandardCharsets.UTF_8));
        Path skillDirectory = Files.createDirectories(repository.resolve(
                ".agents/skills/escaped"));
        try {
            Files.createSymbolicLink(skillDirectory.resolve("SKILL.md"), external);
        } catch (UnsupportedOperationException ex) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
            return;
        }
        gateway = gateway(stub, 5L);

        assertThrows(IllegalStateException.class,
                () -> gateway.preflight(AgentRuntime.CODEX, workingDir.toString()));
    }

    @Test
    void launchShouldFailWhenWorkspaceInventoryChangesAfterSnapshot() throws Exception {
        Path stub = script("workspace-change.sh", "#!/bin/sh\ncat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        Path repository = Files.createDirectories(tempDir.resolve("changing-repository"));
        Files.createDirectory(repository.resolve(".git"));
        Path skillEntry = Files.createDirectories(repository.resolve(
                ".agents/skills/stable")).resolve("SKILL.md");
        Files.write(skillEntry, "# stable\n".getBytes(StandardCharsets.UTF_8));
        gateway = gateway(stub, 5L);
        RuntimePreflightReport report = gateway.preflight(AgentRuntime.CODEX,
                repository.toString());
        AgentExecutionSpec executionSpec = spec("exec-workspace-changed",
                Collections.emptyList(), repository, report);
        Files.write(skillEntry, "# changed\n".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class,
                () -> gateway.start(executionSpec, event -> { }));
        assertRuntimeRootEmpty();
    }

    @Test
    void launchShouldDisableEveryRepositorySkillFromSnapshotUsingSingleRunOverride()
            throws Exception {
        Path arguments = tempDir.resolve("skill-arguments.txt");
        Path stub = script("skill-disable.sh", "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > '" + arguments + "'\n"
                + "cat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        Path repository = Files.createDirectories(tempDir.resolve("skill-repository"));
        Files.createDirectory(repository.resolve(".git"));
        Path entry = Files.createDirectories(repository.resolve(
                ".agents/skills/repo-safe")).resolve("SKILL.md");
        Files.write(entry, "# repo safe\n".getBytes(StandardCharsets.UTF_8));
        gateway = gateway(stub, 5L);
        RuntimePreflightReport report = gateway.preflight(AgentRuntime.CODEX,
                repository.toString());
        Events events = new Events();

        gateway.start(spec("exec-skill-disable", Collections.emptyList(), repository, report),
                events);
        events.awaitTerminal();

        String expectedPath = entry.toRealPath().toString().replace("\\", "\\\\");
        assertOverride(Files.readAllLines(arguments, StandardCharsets.UTF_8),
                "skills.config=[{path=\"" + expectedPath + "\",enabled=false}]");
    }

    private CodexHarnessRuntimeGateway gateway(Path command, long maxRuntimeSeconds) {
        return gateway(command, maxRuntimeSeconds, maxRuntimeSeconds, 1024L * 1024L);
    }

    private CodexHarnessRuntimeGateway gateway(Path command, long maxRuntimeSeconds,
                                               long idleTimeoutSeconds, long maxOutputBytes) {
        HarnessRuntimeProperties runtime = new HarnessRuntimeProperties();
        runtime.setCodexCommand(command.toString());
        runtime.setTempRoot(tempDir.resolve("runtime").toString());
        runtime.setMaxRuntimeSeconds(maxRuntimeSeconds);
        runtime.setIdleTimeoutSeconds(idleTimeoutSeconds);
        runtime.setMaxOutputBytes(maxOutputBytes);
        gatewayProperties = runtime;
        HarnessSecurityProperties security = new HarnessSecurityProperties();
        security.setInheritedEnvironmentVariables(Collections.singleton("PATH"));
        gatewaySecurityProperties = security;
        HarnessSecretResolver secrets = reference -> {
            if ("CODEX_PROVIDER_CREDENTIAL".equals(reference)) {
                return PROVIDER_SECRET;
            }
            if (!"READER_TOKEN".equals(reference)) {
                throw new IllegalStateException("unknown secret reference");
            }
            return SECRET;
        };
        FileSystemRuntimeEvidenceStore evidenceStore = new FileSystemRuntimeEvidenceStore(
                new FileSystemArtifactStore(tempDir.resolve("artifacts")));
        TaskExecutor monitorExecutor = task -> {
            Thread thread = new Thread(task, "harness-runtime-test-monitor");
            thread.setDaemon(true);
            thread.start();
        };
        return new CodexHarnessRuntimeGateway(runtime, security, secrets,
                evidenceStore, monitorExecutor, Clock.systemUTC());
    }

    private AgentExecutionSpec spec(String executionId, List<SelectedMcpServer> servers) {
        RuntimePreflightReport report = gateway.preflight(AgentRuntime.CODEX, tempDir.toString());
        return spec(executionId, servers, tempDir, report);
    }

    private AgentExecutionSpec spec(String executionId, List<SelectedMcpServer> servers,
                                    Path workingDir, RuntimePreflightReport report) {
        String prompt = "perform snapshot task";
        return new AgentExecutionSpec(executionId, "run-1", HarnessStage.ANALYSIS, 1,
                AgentRuntime.CODEX, workingDir.toString(), prompt,
                HarnessHashing.sha256("snapshot"), HarnessHashing.sha256(prompt), servers,
                report.getEnforcementProfile(), report.getWorkspaceInventory(),
                com.example.agentweb.domain.harness.StageContract.mvpDefaults().get(0)
                        .getRequiredOutputArtifacts());
    }

    private SelectedMcpServer reader() {
        McpServerDefinition definition = new McpServerDefinition("reader", "1.0.0", "reader",
                Collections.singleton(HarnessStage.ANALYSIS), Collections.singleton(AgentRuntime.CODEX),
                Arrays.asList("fake-mcp", "--stdio"), Arrays.asList(
                new McpCapability("search", McpCapabilityType.TOOL, CapabilityAccess.READ),
                new McpCapability("update", McpCapabilityType.TOOL, CapabilityAccess.WRITE)),
                Collections.singletonList(new McpSecretReference("READER_API_KEY", "READER_TOKEN")),
                10, 30, HarnessHashing.sha256("reader"));
        return new SelectedMcpServer(definition);
    }

    private Path script(String name, String content) throws Exception {
        String shebang = "#!/bin/sh\n";
        if (!content.startsWith(shebang)) {
            throw new IllegalArgumentException("runtime stub must start with a shell shebang");
        }
        String versionHandler = "if [ \"${1:-}\" = \"--version\" ]; then\n"
                + "  printf '%s\\n' 'codex-cli 0.145.0'\n"
                + "  exit 0\n"
                + "fi\n";
        String artifactBundle = "{\"schemaVersion\":\"harness-artifact-bundle@1\","
                + "\"stage\":\"ANALYSIS\",\"artifacts\":["
                + "{\"artifactId\":\"requirements\",\"artifactType\":\"REQUIREMENT\","
                + "\"contentType\":\"application/json\",\"classification\":\"INTERNAL\","
                + "\"content\":\"{}\"},"
                + "{\"artifactId\":\"acceptance\",\"artifactType\":\"ACCEPTANCE_CRITERIA\","
                + "\"contentType\":\"application/json\",\"classification\":\"INTERNAL\","
                + "\"content\":\"{}\"},"
                + "{\"artifactId\":\"impact\",\"artifactType\":\"IMPACT_ANALYSIS\","
                + "\"contentType\":\"application/json\",\"classification\":\"INTERNAL\","
                + "\"content\":\"{}\"},"
                + "{\"artifactId\":\"questions\",\"artifactType\":\"OPEN_QUESTIONS\","
                + "\"contentType\":\"application/json\",\"classification\":\"INTERNAL\","
                + "\"content\":\"{}\"}]}";
        String outputHandler = "previous=''\n"
                + "for argument in \"$@\"; do\n"
                + "  if [ \"$previous\" = \"--output-last-message\" ]; then\n"
                + "    printf '%s\\n' '" + artifactBundle + "' > \"$argument\"\n"
                + "  fi\n"
                + "  previous=\"$argument\"\n"
                + "done\n";
        return rawScript(name, shebang + versionHandler + outputHandler
                + content.substring(shebang.length()));
    }

    private Path rawScript(String name, String content) throws Exception {
        Path script = tempDir.resolve(name);
        Files.write(script, content.getBytes(StandardCharsets.UTF_8));
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private HarnessRuntimeProperties gatewayProperties() {
        return gatewayProperties;
    }

    private void assertOverride(List<String> arguments, String expected) {
        for (int index = 0; index + 1 < arguments.size(); index++) {
            if ("-c".equals(arguments.get(index)) && expected.equals(arguments.get(index + 1))) {
                return;
            }
        }
        throw new AssertionError("missing Codex -c override: " + expected
                + " actual=" + arguments);
    }

    private void assertRuntimeRootEmpty() throws Exception {
        Path root = tempDir.resolve("runtime");
        if (Files.notExists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            assertEquals(0L, children.count());
        }
    }

    private String evidenceText(String executionId) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.walk(tempDir.resolve("artifacts"))) {
            Path artifact = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains(
                            HarnessHashing.sha256("runtime-jsonl-" + executionId)))
                    .findFirst().orElseThrow(AssertionError::new);
            return new String(Files.readAllBytes(artifact), StandardCharsets.UTF_8);
        }
    }

    private static final class Events implements com.example.agentweb.app.harness.port.RuntimeEventSink {

        private final List<RuntimeEvent> events = new CopyOnWriteArrayList<RuntimeEvent>();
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void onEvent(RuntimeEvent event) {
            events.add(event);
            if (event.getSignal().getType() == RuntimeExecutionSignalType.SUCCEEDED
                    || event.getSignal().getType() == RuntimeExecutionSignalType.FAILED
                    || event.getSignal().getType() == RuntimeExecutionSignalType.TIMED_OUT
                    || event.getSignal().getType() == RuntimeExecutionSignalType.CANCELLED) {
                terminal.countDown();
            }
        }

        private void awaitTerminal() throws InterruptedException {
            assertTrue(terminal.await(8L, TimeUnit.SECONDS));
        }

        private RuntimeEvent terminal() {
            return events.get(events.size() - 1);
        }

        private RuntimeEvent started() {
            return events.stream().filter(event ->
                    event.getSignal().getType() == RuntimeExecutionSignalType.STARTED)
                    .findFirst().orElseThrow(AssertionError::new);
        }
    }
}
