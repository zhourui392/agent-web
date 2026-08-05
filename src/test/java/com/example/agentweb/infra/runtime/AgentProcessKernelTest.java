package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeAttachmentExpectation;
import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeEventType;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeSemanticEvent;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.McpCapability;
import com.example.agentweb.domain.capability.McpCapabilityType;
import com.example.agentweb.domain.capability.McpSecretReference;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公共 Agent 进程内核的启动、输出、终止、限额、进程树和清理合同。
 *
 * @author alex
 * @since 2026-08-01
 */
class AgentProcessKernelTest {

    private static final String SECRET = "kernel-provider-secret";

    @TempDir
    Path tempDir;

    private final List<AgentProcessKernel> kernels = new ArrayList<AgentProcessKernel>();
    private final List<ExecutorService> executors = new ArrayList<ExecutorService>();

    @AfterEach
    void tearDown() {
        for (AgentProcessKernel kernel : kernels) {
            kernel.close();
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    @Test
    void asynchronouslyRunsCodexJsonlAndCleansUp()
            throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary"));
        Path script = script("success.sh", "#!/bin/sh\n"
                + "test -d \"${AGENT_WORKBENCH_ATTACHMENT_DIR-}\" || exit 42\n"
                + "cat >/dev/null\n"
                + "printf '%s\\n' \"{\\\"type\\\":\\\"item.completed\\\","
                + "\\\"item\\\":{\\\"type\\\":\\\"agent_message\\\","
                + "\\\"text\\\":\\\"done\\\"}}\"\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        RuntimeProcessRegistry registry = new RuntimeProcessRegistry();
        AgentProcessKernel kernel = kernel(script, "runtime-success", registry);
        AgentExecutionPlan plan = RuntimePlanFixtures.readOnly("exec-success", primary,
                Collections.singletonList(primary));
        Events events = new Events();

        RuntimeHandle handle = kernel.start(plan, events);

        events.awaitTerminal();
        assertEquals("exec-success", handle.getExecutionId());
        assertEquals(RuntimeState.TERMINATED, kernel.observe(handle).getState());
        assertEquals(RuntimeTerminationReason.COMPLETED,
                kernel.observe(handle).termination().orElseThrow(AssertionError::new).getReason());
        assertTrue(events.types().contains(RuntimeEventType.STARTED));
        assertTrue(events.types().contains(RuntimeEventType.OUTPUT));
        assertEquals(RuntimeEventType.TERMINATED, events.terminal().getType());
        assertStrictlyIncreasingSequences(events.events);
        assertRuntimeRootEmpty(tempDir.resolve("runtime-success"));
    }

    @Test
    void enforcesOutputLimitAndHardTimeoutAsTechnicalTerminalFacts() throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-limits"));
        Path noisy = script("noisy.sh", "#!/bin/sh\ncat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"item.completed\","
                + "\"payload\":\"12345678901234567890\"}'\n");
        RuntimeProcessRegistry limitedRegistry = new RuntimeProcessRegistry();
        AgentProcessKernel limitedKernel = kernel(noisy, "runtime-limit", limitedRegistry);
        AgentExecutionPlan limitedPlan = RuntimePlanFixtures.plan("exec-limit", primary,
                Collections.singletonList(primary), Collections.<Path>emptyList(),
                SandboxMode.READ_ONLY, Duration.ofSeconds(5L), 16L);
        Events limitedEvents = new Events();

        RuntimeHandle limitedHandle = limitedKernel.start(limitedPlan, limitedEvents);
        limitedEvents.awaitTerminal();

        assertEquals(RuntimeTerminationReason.OUTPUT_LIMIT,
                limitedKernel.observe(limitedHandle).termination()
                        .orElseThrow(AssertionError::new).getReason());
        assertRuntimeRootEmpty(tempDir.resolve("runtime-limit"));

        Path idle = script("idle.sh", "#!/bin/sh\nsleep 20\n");
        RuntimeProcessRegistry timeoutRegistry = new RuntimeProcessRegistry();
        AgentProcessKernel timeoutKernel = kernel(idle, "runtime-timeout", timeoutRegistry);
        AgentExecutionPlan timeoutPlan = RuntimePlanFixtures.plan("exec-timeout", primary,
                Collections.singletonList(primary), Collections.<Path>emptyList(),
                SandboxMode.READ_ONLY, Duration.ofMillis(150L), 1024L);
        Events timeoutEvents = new Events();

        RuntimeHandle timeoutHandle = timeoutKernel.start(timeoutPlan, timeoutEvents);
        timeoutEvents.awaitTerminal();

        assertEquals(RuntimeTerminationReason.TIMEOUT,
                timeoutKernel.observe(timeoutHandle).termination()
                        .orElseThrow(AssertionError::new).getReason());
        assertRuntimeRootEmpty(tempDir.resolve("runtime-timeout"));
    }

    @Test
    void requestStopIsIdempotentAndTerminatesDescendantProcessTree() throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-stop"));
        Path childPid = tempDir.resolve("child.pid");
        Path script = script("tree.sh", "#!/bin/sh\n"
                + "sleep 20 &\n"
                + "printf '%s' \"$!\" > '" + childPid + "'\n"
                + "wait\n");
        RuntimeProcessRegistry registry = new RuntimeProcessRegistry();
        AgentProcessKernel kernel = kernel(script, "runtime-stop", registry);
        AgentExecutionPlan plan = RuntimePlanFixtures.readOnly("exec-stop", primary,
                Collections.singletonList(primary));
        Events events = new Events();
        RuntimeHandle handle = kernel.start(plan, events);
        awaitFile(childPid);
        String pid = new String(Files.readAllBytes(childPid), StandardCharsets.UTF_8);

        kernel.requestStop(handle);
        kernel.requestStop(handle);

        events.awaitTerminal();
        assertEquals(RuntimeTerminationReason.REQUESTED_STOP,
                kernel.observe(handle).termination().orElseThrow(AssertionError::new).getReason());
        assertEventuallyNotAlive(pid);
        assertRuntimeRootEmpty(tempDir.resolve("runtime-stop"));
    }

    @Test
    void launchesWithExactMcpOverridesAndRedactsResolvedMcpSecret() throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-mcp"));
        Path script = script("mcp.sh", "#!/bin/sh\n"
                + "case \"$*\" in *mcp_servers.repository-query.command*) ;; "
                + "*) exit 41 ;; esac\n"
                + "cat >/dev/null\n"
                + "printf '%s\\n' \"{\\\"type\\\":\\\"item.completed\\\","
                + "\\\"item\\\":{\\\"type\\\":\\\"agent_message\\\","
                + "\\\"text\\\":\\\"mcpSecret=${MCP_QUERY_KEY-}\\\"}}\"\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        McpServerDefinition definition = mcpDefinition();
        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                "policy@1", "profile", "1.0.0",
                CanonicalHashing.sha256("profile"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new ResolvedMcpServerBinding(
                        definition.getId(), definition.getVersion(),
                        definition.getConfigurationHash(), CapabilityAccess.READ,
                        "STDIO")), Collections.emptyList(), "CODEX");
        AgentExecutionPlan plan = withBinding(
                RuntimePlanFixtures.readOnly("exec-mcp", primary,
                        Collections.singletonList(primary)), binding);
        RuntimeCapabilityMaterializer capabilityMaterializer =
                new RuntimeCapabilityMaterializer(
                        Collections::emptyList,
                        () -> Collections.singletonList(definition),
                        reference -> {
                            assertEquals("MCP_QUERY_REFERENCE", reference);
                            return SECRET.toCharArray();
                        });
        RuntimeProcessRegistry registry = new RuntimeProcessRegistry();
        AgentProcessKernel kernel = kernel(script, "runtime-mcp",
                registry, capabilityMaterializer);
        Events events = new Events();

        RuntimeHandle handle = kernel.start(plan, events);

        events.awaitTerminal();
        assertEquals(RuntimeTerminationReason.COMPLETED,
                kernel.observe(handle).termination()
                        .orElseThrow(AssertionError::new).getReason());
        assertTrue(events.events.stream().flatMap(event ->
                        event.getSemanticEvents().stream())
                .anyMatch(event -> String.valueOf(
                        event.getData().get("content"))
                        .contains("[REDACTED]")));
        assertTrue(events.events.stream().noneMatch(event ->
                event.getSafePayload().contains(SECRET)));
        assertRuntimeRootEmpty(tempDir.resolve("runtime-mcp"));
    }

    @Test
    void executionContextShouldEnhanceFinishedToolWithObservedDurationOnly()
            throws Exception {
        Path primary = Files.createDirectory(
                tempDir.resolve("primary-tool-duration"));
        Path script = script("tool-duration.sh", "#!/bin/sh\n"
                + "cat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"item.started\",\"item\":{"
                + "\"id\":\"tool-1\",\"type\":\"command_execution\","
                + "\"command\":\"pwd\",\"status\":\"in_progress\"}}'\n"
                + "printf '%s\\n' '{\"type\":\"item.completed\",\"item\":{"
                + "\"id\":\"tool-1\",\"type\":\"command_execution\","
                + "\"command\":\"pwd\","
                + "\"aggregated_output\":\"/home/private tool-secret\","
                + "\"exit_code\":0,\"status\":\"completed\"}}'\n"
                + "printf '%s\\n' '{\"type\":\"turn.completed\"}'\n");
        RuntimeProcessRegistry registry = new RuntimeProcessRegistry();
        AgentProcessKernel kernel = kernel(
                script, "runtime-tool-duration",
                registry, times(10_000_000L, 17_000_000L));
        AgentExecutionPlan plan = RuntimePlanFixtures.readOnly(
                "exec-tool-duration", primary,
                Collections.singletonList(primary));
        Events events = new Events();

        kernel.start(plan, events);
        events.awaitTerminal();

        RuntimeSemanticEvent started = semantic(events, "tool_started");
        RuntimeSemanticEvent finished = semantic(events, "tool_finished");
        assertFalse(started.getData().containsKey("durationMs"));
        assertEquals(7L, finished.getData().get("durationMs"));
        assertFalse(finished.getData().toString().contains("pwd"));
        assertFalse(finished.getData().toString().contains("/home/private"));
        assertFalse(finished.getData().toString().contains("tool-secret"));
    }

    @Test
    void changedAttachmentShouldPreventProcessStart() throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-attachment"));
        Path marker = tempDir.resolve("attachment-process-started");
        Path script = script("attachment.sh", "#!/bin/sh\n"
                + "touch '" + marker + "'\n"
                + "cat >/dev/null\n");
        byte[] approved = "approved".getBytes(StandardCharsets.UTF_8);
        Path attachment = Files.write(primary.resolve("design.md"), approved);
        AgentExecutionPlan base = RuntimePlanFixtures.readOnly(
                "exec-attachment", primary,
                Collections.singletonList(primary));
        RuntimeAttachmentExpectation expectation =
                new RuntimeAttachmentExpectation(
                        "primary", primary.toString(), "design.md",
                        CanonicalHashing.sha256(approved), approved.length);
        AgentExecutionPlan plan = new AgentExecutionPlan(
                base.getExecutionIdentity(), base.getRuntimeSelection(),
                base.getPromptPayload(), base.getWorkspaceLayout(),
                base.getCapabilityBinding(), base.getRuntimeLimits(),
                Collections.singletonList(expectation));
        Files.write(attachment, "tampered".getBytes(StandardCharsets.UTF_8));
        RuntimeProcessRegistry registry = new RuntimeProcessRegistry();
        AgentProcessKernel kernel = kernel(
                script, "runtime-attachment", registry);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> kernel.start(plan, new Events()));

        assertFalse(Files.exists(marker));
        assertTrue(registry.activeHandles().isEmpty());
        assertFalse(failure.toString().contains(primary.toString()));
        assertFalse(failure.toString().contains("tampered"));
    }

    @Test
    void blockedHighImpactIntentStopsProcessBeforeFollowingSideEffectAndEmitsSafeEvent()
            throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-blocked"));
        Path forbiddenEffect = tempDir.resolve("forbidden-effect");
        Path script = script("blocked.sh", "#!/bin/sh\n"
                + "cat >/dev/null\n"
                + "printf '%s\\n' '{\"type\":\"item.started\",\"item\":{"
                + "\"id\":\"command-1\",\"type\":\"command_execution\","
                + "\"command\":\"git push origin master\","
                + "\"status\":\"in_progress\"}}'\n"
                + "sleep 2\n"
                + "touch '" + forbiddenEffect + "'\n");
        RuntimeProcessRegistry registry = new RuntimeProcessRegistry();
        AgentProcessKernel kernel = kernel(script, "runtime-blocked", registry);
        AgentExecutionPlan plan = RuntimePlanFixtures.plan(
                "exec-blocked", primary, Collections.singletonList(primary),
                Collections.singletonList(primary), SandboxMode.WORKSPACE_WRITE,
                Duration.ofSeconds(5L), 1024L * 1024L);
        Events events = new Events();

        RuntimeHandle handle = kernel.start(plan, events);

        events.awaitTerminal();
        assertEquals(RuntimeTerminationReason.SECURITY_POLICY,
                kernel.observe(handle).termination()
                        .orElseThrow(AssertionError::new).getReason());
        assertFalse(Files.exists(forbiddenEffect));
        assertTrue(events.events.stream().flatMap(event ->
                        event.getSemanticEvents().stream())
                .anyMatch(event -> "operation_blocked".equals(
                        event.getEventType())));
        assertTrue(events.events.stream().noneMatch(event ->
                event.getSafePayload().contains("git push")));
    }

    private AgentProcessKernel kernel(Path command, String runtimeDirectory,
                                      RuntimeProcessRegistry registry) {
        return kernel(command, runtimeDirectory, registry,
                new RuntimeCapabilityMaterializer(
                        Collections::emptyList, Collections::emptyList,
                        reference -> new char[0]));
    }

    private AgentProcessKernel kernel(
            Path command, String runtimeDirectory,
            RuntimeProcessRegistry registry,
            LongSupplier toolNanoTimeSource) {
        return kernel(
                command, runtimeDirectory, registry,
                new RuntimeCapabilityMaterializer(
                        Collections::emptyList, Collections::emptyList,
                        reference -> new char[0]),
                toolNanoTimeSource);
    }

    private AgentProcessKernel kernel(Path command, String runtimeDirectory,
                                      RuntimeProcessRegistry registry,
                                      RuntimeCapabilityMaterializer capabilityMaterializer) {
        return kernel(
                command, runtimeDirectory, registry,
                capabilityMaterializer, System::nanoTime);
    }

    private AgentProcessKernel kernel(
            Path command, String runtimeDirectory,
            RuntimeProcessRegistry registry,
            RuntimeCapabilityMaterializer capabilityMaterializer,
            LongSupplier toolNanoTimeSource) {
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "runtime-kernel-test");
            thread.setDaemon(true);
            return thread;
        });
        executors.add(executor);
        AgentProcessKernel kernel = new AgentProcessKernel(
                new RuntimeCommandFactory(command.toString()),
                new RuntimeWorkspaceMaterializer(tempDir.resolve(runtimeDirectory)),
                capabilityMaterializer,
                new RuntimeEventDecoder(new RuntimeOutputRedactor()),
                registry, new RuntimeCleanup(), executor,
                toolNanoTimeSource);
        kernels.add(kernel);
        return kernel;
    }

    private AgentExecutionPlan withBinding(
            AgentExecutionPlan plan, ResolvedCapabilityBinding binding) {
        return new AgentExecutionPlan(
                plan.getExecutionIdentity(), plan.getRuntimeSelection(),
                plan.getPromptPayload(), plan.getWorkspaceLayout(), binding,
                plan.getRuntimeLimits(), plan.getAttachmentExpectations());
    }

    private McpServerDefinition mcpDefinition() {
        Set<String> useCases = Collections.singleton("WORKBENCH_STAGE");
        Set<String> runtimes = Collections.singleton("CODEX");
        return new McpServerDefinition(
                "repository-query", "1.0.0", "repository query",
                useCases, runtimes, Arrays.asList("repository-mcp", "--stdio"),
                Collections.singletonList(new McpCapability(
                        "read_repository", McpCapabilityType.TOOL,
                        CapabilityAccess.READ)),
                Collections.singletonList(new McpSecretReference(
                        "MCP_QUERY_KEY", "MCP_QUERY_REFERENCE")),
                10, 30, CanonicalHashing.sha256("mcp-definition"));
    }

    private Path script(String name, String content) throws Exception {
        Path script = tempDir.resolve(name);
        Files.write(script, content.getBytes(StandardCharsets.UTF_8));
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (Files.notExists(file) && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(Files.exists(file));
    }

    private void assertEventuallyNotAlive(String pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (isAlive(pid) && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        assertFalse(isAlive(pid), "descendant process is still alive: " + pid);
    }

    private boolean isAlive(String pid) throws Exception {
        Process probe = new ProcessBuilder("kill", "-0", pid).start();
        return probe.waitFor() == 0;
    }

    private void assertRuntimeRootEmpty(Path root) throws Exception {
        if (Files.notExists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            assertEquals(0L, children.count());
        }
    }

    private void assertStrictlyIncreasingSequences(List<RuntimeEvent> events) {
        long previous = 0L;
        for (RuntimeEvent event : events) {
            assertTrue(event.getSequence() > previous,
                    "runtime event sequence must be strictly increasing");
            previous = event.getSequence();
        }
    }

    private static RuntimeSemanticEvent semantic(
            Events events, String eventType) {
        return events.events.stream()
                .flatMap(event -> event.getSemanticEvents().stream())
                .filter(event -> eventType.equals(event.getEventType()))
                .findFirst()
                .orElseThrow(AssertionError::new);
    }

    private static LongSupplier times(long... observations) {
        AtomicInteger index = new AtomicInteger();
        return () -> observations[Math.min(
                index.getAndIncrement(), observations.length - 1)];
    }

    private static final class Events implements RuntimeEventSink {

        private final List<RuntimeEvent> events = new CopyOnWriteArrayList<RuntimeEvent>();
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void onEvent(RuntimeEvent event) {
            events.add(event);
            if (event.getType() == RuntimeEventType.TERMINATED) {
                terminal.countDown();
            }
        }

        private void awaitTerminal() throws InterruptedException {
            assertTrue(terminal.await(8L, TimeUnit.SECONDS));
        }

        private RuntimeEvent terminal() {
            return events.get(events.size() - 1);
        }

        private List<RuntimeEventType> types() {
            List<RuntimeEventType> types = new ArrayList<RuntimeEventType>();
            for (RuntimeEvent event : events) {
                types.add(event.getType());
            }
            return types;
        }
    }
}
