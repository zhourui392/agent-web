package com.example.agentweb;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.harness.HarnessHashing;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.example.agentweb.infra.harness.HarnessSecretResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 HTTP → Snapshot/MCP → Codex Stub → Runtime Event → SQLite/Artifact 的纵向流程。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Tag("spring-flow")
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class HarnessM3FlowTest {

    private static final String FAKE_SECRET = "secret-value-never-persist";
    private static final Path ROOT = createTempRoot();
    private static final Path WORKSPACE = createWorkspace();
    private static final Path MCP_ROOT = createMcpCatalog();
    private static final Path COMMAND_LOG = ROOT.resolve("codex-arguments.log");
    private static final Path CODEX_STUB = createCodexStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @MockBean
    private WorkspacePathPolicy workspacePathPolicy;

    @MockBean
    private HarnessSecretResolver secretResolver;

    @MockBean
    private WorkspaceBaselineGateway workspaceBaselineGateway;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + ROOT.resolve("harness-m3-flow.db").toAbsolutePath());
        registry.add("agent.harness.enabled", () -> "true");
        registry.add("agent.harness.artifact-root",
                () -> ROOT.resolve("artifacts").toAbsolutePath().toString());
        registry.add("agent.harness.mcp-server-root", () -> MCP_ROOT.toString());
        registry.add("agent.harness.runtime.codex-command", () -> CODEX_STUB.toString());
        registry.add("agent.harness.runtime.temp-root",
                () -> ROOT.resolve("runtime").toAbsolutePath().toString());
        registry.add("agent.harness.runtime.idle-timeout-seconds", () -> "5");
        registry.add("agent.harness.runtime.max-runtime-seconds", () -> "5");
        registry.add("agent.harness.security.allowed-mcp-server-ids[0]", () -> "reader");
        registry.add("agent.public-access.enabled", () -> "false");
    }

    @Test
    void shouldRunAuthorizedReadOnlyMcpAndPersistSanitizedEvidence() throws Exception {
        prepareUserAndWorkspace();
        String runId = createAndStart("m3-success", "M3 success");
        JsonNode snapshot = resolve(runId, "perform normal runtime work");
        String snapshotHash = snapshot.path("snapshotHash").asText();
        assertEquals("M3.1", snapshot.path("schemaVersion").asText());
        assertEquals("reader", snapshot.path("selectedMcpServers").get(0).path("id").asText());
        assertEquals("codex-cli 0.145.0",
                snapshot.path("runtimeEnforcement").path("runtimeVersion").asText());
        assertFalse(snapshot.toString().contains("secret-value-never-persist"));

        MvcResult launched = mvc.perform(post("/api/harness/runs/" + runId
                        + "/stages/ANALYSIS/executions")
                        .header("Idempotency-Key", "m3-success-launch"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attemptNumber").value(1))
                .andReturn();
        JsonNode launchBody = objectMapper.readTree(launched.getResponse().getContentAsString());
        String executionId = launchBody.path("executionId").asText();
        String canonicalLocation = launched.getResponse().getHeader("Location");

        JsonNode execution = awaitExecution(runId, "SUCCEEDED");
        assertEquals(executionId, execution.path("executionId").asText());
        assertEquals("SUCCEEDED", execution.path("cleanupStatus").asText());
        assertTrue(execution.path("evidenceReference").asText().startsWith("artifact:"));
        assertEquals("reader", execution.path("selectedMcpServers").get(0).path("id").asText());
        assertFalse(execution.has("runtimeHandle"));
        assertFalse(execution.toString().contains("pid:"));
        assertFalse(execution.toString().contains("command"));
        assertFalse(execution.toString().contains("secretReferences"));
        assertFalse(execution.toString().contains(FAKE_SECRET));
        assertRuntimeArguments();

        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/executions")
                        .header("Idempotency-Key", "m3-success-launch"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", canonicalLocation))
                .andExpect(jsonPath("$.executionId").value(executionId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.duplicated").value(true));
        mvc.perform(post("/api/harness/runs/" + runId + "/stages/DESIGN/executions")
                        .header("Idempotency-Key", "m3-success-launch"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "HARNESS_EXECUTION_IDEMPOTENCY_CONFLICT"));
        assertLaunchCount(1L);

        mvc.perform(get(snapshotUrl(runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotHash").value(snapshotHash));
        String evidence = artifactText(execution.path("executionId").asText());
        assertTrue(evidence.contains("turn.completed"));
        assertTrue(evidence.contains("[REDACTED]"));
        assertFalse(evidence.contains(FAKE_SECRET));
        assertEquals(0, persistedSecretOccurrences());
        assertRuntimeRootEmpty();
    }

    @Test
    void shouldPersistCancellationIntentThenTerminateStubAndRemainCancelled() throws Exception {
        prepareUserAndWorkspace();
        String runId = createAndStart("m3-cancel", "M3 cancel");
        String snapshotHash = resolve(runId, "perform slow runtime work")
                .path("snapshotHash").asText();
        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/executions")
                        .header("Idempotency-Key", "m3-cancel-launch"))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/harness/runs/" + runId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"operator stop\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLING"));

        JsonNode execution = awaitExecution(runId, "CANCELLED");
        assertEquals("SUCCEEDED", execution.path("cleanupStatus").asText());
        mvc.perform(get("/api/harness/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get(snapshotUrl(runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotHash").value(snapshotHash));
        assertRuntimeArguments();
        assertLaunchCount(1L);
        assertRuntimeRootEmpty();
    }

    private void prepareUserAndWorkspace() {
        write(COMMAND_LOG, "");
        when(currentUserProvider.currentUserId()).thenReturn("admin");
        when(workspacePathPolicy.requireExistingDirectory(anyString()))
                .thenReturn(WORKSPACE.toString());
        when(workspaceBaselineGateway.capture(anyString())).thenReturn(workspaceBaseline());
        when(secretResolver.resolve("READER_TOKEN")).thenReturn(FAKE_SECRET);
    }

    private String createAndStart(String key, String title) throws Exception {
        MvcResult created = mvc.perform(post("/api/harness/runs")
                        .header("Idempotency-Key", key + "-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"workingDir\":\""
                                + jsonEscape(WORKSPACE.toString())
                                + "\",\"agentType\":\"CODEX\",\"environment\":\"test\","
                                + "\"originalRequirement\":\"Harness M3 flow\"}"))
                .andExpect(status().isCreated()).andReturn();
        String runId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("runId").asText();
        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/start")
                        .header("Idempotency-Key", key + "-start"))
                .andExpect(status().isAccepted());
        return runId;
    }

    private JsonNode resolve(String runId, String input) throws Exception {
        MvcResult result = mvc.perform(post("/api/harness/runs/" + runId
                        + "/stages/ANALYSIS/capability-snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalTags\":[\"java\"],"
                                + "\"explicitMcpServerIds\":[\"reader\"],"
                                + "\"requiredMcpServerIds\":[\"reader\"],"
                                + "\"grantedMcpServerIds\":[\"reader\"],"
                                + "\"readableFileRoots\":[\"workspace\"],"
                                + "\"currentInput\":\"" + input + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode awaitExecution(String runId, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8L);
        JsonNode execution = null;
        while (System.nanoTime() < deadline) {
            MvcResult result = mvc.perform(get(executionUrl(runId)))
                    .andExpect(status().isOk()).andReturn();
            execution = objectMapper.readTree(result.getResponse().getContentAsString());
            if (expected.equals(execution.path("status").asText())) {
                return execution;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("execution did not reach " + expected + ": " + execution);
    }

    private String executionUrl(String runId) {
        return "/api/harness/runs/" + runId + "/stages/ANALYSIS/attempts/1/execution";
    }

    private String snapshotUrl(String runId) {
        return "/api/harness/runs/" + runId
                + "/stages/ANALYSIS/attempts/1/capability-snapshot";
    }

    private String artifactText(String executionId) throws Exception {
        try (Stream<Path> files = Files.walk(ROOT.resolve("artifacts"))) {
            Path artifact = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains(
                            HarnessHashing.sha256("runtime-jsonl-" + executionId)))
                    .findFirst().orElseThrow(AssertionError::new);
            return new String(Files.readAllBytes(artifact), StandardCharsets.UTF_8);
        }
    }

    private void assertRuntimeRootEmpty() throws Exception {
        Path runtime = ROOT.resolve("runtime");
        if (Files.notExists(runtime)) {
            return;
        }
        try (Stream<Path> children = Files.list(runtime)) {
            assertEquals(0L, children.count());
        }
    }

    private void assertRuntimeArguments() throws Exception {
        String arguments = new String(Files.readAllBytes(COMMAND_LOG), StandardCharsets.UTF_8);
        assertTrue(arguments.contains("<--ignore-user-config>"));
        assertTrue(arguments.contains("<--ignore-rules>"));
        assertTrue(arguments.contains("<mcp_servers.reader.command=\"fake-mcp\">"));
        assertTrue(arguments.contains("<mcp_servers.reader.args=[\"--stdio\"]>"));
        assertTrue(arguments.contains("<mcp_servers.reader.env_vars=[\"READER_API_KEY\"]>"));
        assertTrue(arguments.contains("<mcp_servers.reader.required=true>"));
        assertTrue(arguments.contains("<mcp_servers.reader.startup_timeout_sec=10>"));
        assertTrue(arguments.contains("<mcp_servers.reader.tool_timeout_sec=30>"));
        assertTrue(arguments.contains("<mcp_servers.reader.enabled_tools=[\"search\"]>"));
        assertTrue(arguments.contains("<mcp_servers.reader.disabled_tools=[\"update\"]>"));
        assertTrue(arguments.contains(
                "<mcp_servers.reader.default_tools_approval_mode=\"writes\">"));
        assertTrue(arguments.contains("<skills.config=[{path=\""));
        assertTrue(arguments.contains("/.agents/skills/domain-modeling-audit/SKILL.md\""));
        assertTrue(arguments.contains(",enabled=false}]>"));
        assertFalse(arguments.contains(FAKE_SECRET));
    }

    private void assertLaunchCount(long expected) throws Exception {
        try (Stream<String> lines = Files.lines(COMMAND_LOG, StandardCharsets.UTF_8)) {
            assertEquals(expected, lines.filter("LAUNCH"::equals).count());
        }
    }

    private static Path createWorkspace() {
        Path workspace = createDirectory(ROOT.resolve("workspace"));
        Path skillEntry = createDirectory(workspace.resolve(
                ".agents/skills/domain-modeling-audit")).resolve("SKILL.md");
        write(skillEntry, "# Domain Modeling Audit\n\n"
                + "提取业务不变量、强一致性边界和变化点，给出应由聚合根、领域服务或端口承担的职责。\n");
        return workspace;
    }

    private static Path createMcpCatalog() {
        Path directory = createDirectory(ROOT.resolve("mcp-servers/reader/1.0.0"));
        String manifest = "schemaVersion: '1'\n"
                + "id: reader\n"
                + "version: 1.0.0\n"
                + "description: Fake read-only MCP\n"
                + "stages: [ANALYSIS]\n"
                + "runtimes: [CODEX]\n"
                + "command: [fake-mcp, --stdio]\n"
                + "startupTimeoutSeconds: 10\n"
                + "toolTimeoutSeconds: 30\n"
                + "capabilities:\n"
                + "  - id: search\n"
                + "    type: TOOL\n"
                + "    access: READ\n"
                + "  - id: update\n"
                + "    type: TOOL\n"
                + "    access: WRITE\n"
                + "secrets:\n"
                + "  - environmentVariable: READER_API_KEY\n"
                + "    reference: READER_TOKEN\n";
        write(directory.resolve("manifest.yml"), manifest);
        return ROOT.resolve("mcp-servers");
    }

    private static Path createCodexStub() {
        Path stub = ROOT.resolve("codex-stub.sh");
        write(stub, "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then\n"
                + "  printf '%s\\n' 'codex-cli 0.145.0'\n"
                + "  exit 0\n"
                + "fi\n"
                + "printf '%s\\n' 'LAUNCH' >> \"" + COMMAND_LOG.toAbsolutePath() + "\"\n"
                + "previous=''\n"
                + "for arg in \"$@\"; do\n"
                + "  printf '<%s>\\n' \"$arg\" >> \""
                + COMMAND_LOG.toAbsolutePath() + "\"\n"
                + "  if [ \"$previous\" = \"--output-last-message\" ]; then\n"
                + "    output_last_message=$arg\n"
                + "  fi\n"
                + "  previous=$arg\n"
                + "done\n"
                + "test ! -f \"$CODEX_HOME/config.toml\" || exit 31\n"
                + "test \"$READER_API_KEY\" = 'secret-value-never-persist' || exit 35\n"
                + "input=$(cat)\n"
                + "case \"$input\" in\n"
                + "  *slow*) sleep 20 ;;\n"
                + "  *) printf '%s\\n' '{\"type\":\"thread.started\",\"thread_id\":\"m3\","
                + "\"secret\":\"secret-value-never-persist\"}' ;"
                + " printf '%s\\n' '{\"type\":\"turn.completed\"}' ;"
                + " printf '%s\\n' '"
                + analysisArtifactBundle().replace("'", "'\\''")
                + "' > \"$output_last_message\" ;;\n"
                + "esac\n");
        if (!stub.toFile().setExecutable(true)) {
            throw new IllegalStateException("could not make Codex stub executable");
        }
        return stub;
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("agent-web-harness-m3-flow-");
        } catch (IOException ex) {
            throw new IllegalStateException("could not create Harness M3 temp root", ex);
        }
    }

    private static Path createDirectory(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException("could not create Harness M3 directory", ex);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("could not write Harness M3 fixture", ex);
        }
    }

    private WorkspaceBaseline workspaceBaseline() {
        return WorkspaceBaseline.capture(WORKSPACE.toString(), "main",
                repeat('a', 40), true, repeat('b', 64), Instant.parse("2026-07-23T00:00:00Z"));
    }

    private String repeat(char value, int count) {
        return String.join("", java.util.Collections.nCopies(count, String.valueOf(value)));
    }

    private static String analysisArtifactBundle() {
        return "{\"schemaVersion\":\"harness-artifact-bundle@1\",\"stage\":\"ANALYSIS\","
                + "\"artifacts\":["
                + "{\"artifactId\":\"requirements\",\"artifactType\":\"REQUIREMENT\","
                + "\"contentType\":\"application/json\",\"classification\":\"INTERNAL\","
                + "\"content\":\"{\\\"requirements\\\":[{\\\"id\\\":\\\"REQ-1\\\"}]}\"},"
                + "{\"artifactId\":\"acceptance\",\"artifactType\":\"ACCEPTANCE_CRITERIA\","
                + "\"contentType\":\"application/json\",\"classification\":\"INTERNAL\","
                + "\"content\":\"{\\\"acceptanceCriteria\\\":[{\\\"id\\\":\\\"AC-1\\\","
                + "\\\"requirementId\\\":\\\"REQ-1\\\",\\\"description\\\":\\\"observable\\\","
                + "\\\"verification\\\":\\\"test\\\"}]}\"},"
                + "{\"artifactId\":\"impact\",\"artifactType\":\"IMPACT_ANALYSIS\","
                + "\"contentType\":\"text/markdown\",\"classification\":\"INTERNAL\","
                + "\"content\":\"impact\"},"
                + "{\"artifactId\":\"questions\",\"artifactType\":\"OPEN_QUESTIONS\","
                + "\"contentType\":\"application/json\",\"classification\":\"INTERNAL\","
                + "\"content\":\"{\\\"questions\\\":[]}\"}]}";
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private int persistedSecretOccurrences() {
        int snapshotRows = jdbc.queryForObject("SELECT COUNT(*) FROM harness_capability_snapshot "
                        + "WHERE selected_mcp_servers_json LIKE ? OR final_prompt LIKE ?",
                Integer.class, "%" + FAKE_SECRET + "%", "%" + FAKE_SECRET + "%").intValue();
        int eventRows = jdbc.queryForObject("SELECT COUNT(*) FROM harness_runtime_event "
                        + "WHERE summary LIKE ? OR evidence_reference LIKE ?",
                Integer.class, "%" + FAKE_SECRET + "%", "%" + FAKE_SECRET + "%").intValue();
        return snapshotRows + eventRows;
    }
}
