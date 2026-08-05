package com.example.agentweb.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 JVM 重启下 Workbench 写 Run 未知终态与写租约恢复对账。
 *
 * @author alex
 * @since 2026-08-01
 */
@Tag("process-integration")
@EnabledOnOs(OS.LINUX)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class WorkbenchRuntimeRestartRecoveryProcessIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_PASSWORD =
            "Workbench-Restart-Recovery-2026!";
    private static final String WAIT_MARKER =
            "[E2E_RESTART_RECOVERY_WAIT]";

    @TempDir
    Path temporaryDirectory;

    private Process application;
    private Path applicationLog;

    @AfterEach
    void stopApplication() throws Exception {
        if (application != null && application.isAlive()) {
            application.destroyForcibly();
            application.waitFor(20L, TimeUnit.SECONDS);
        }
    }

    @Test
    void restartShouldInterruptUnknownWriteRunReleaseLeaseAndAllowNextWrite()
            throws Exception {
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        Path fixture = projectRoot.resolve(
                "tests/e2e/fixtures/workbench-runtime-stub.sh");
        assertTrue(Files.isExecutable(fixture),
                "deterministic Runtime fixture must be executable");
        Path database = temporaryDirectory.resolve("workbench-restart.db");
        Path runtimeRoot = temporaryDirectory.resolve("runtime");
        Path workspaceRoot = temporaryDirectory.resolve("workspace");
        Path repository = createGitRepository(workspaceRoot, "restart-service");
        Path invocationLog = repository.resolve(
                ".agent-web-restart-runtime-invocations");
        int port = freePort();

        startApplication(projectRoot, fixture, database, runtimeRoot,
                port, "first");
        awaitReady(port);
        String cookie = login(port);
        publishRestartRecoveryStage(port, cookie);
        final String firstCookie = cookie;
        String repositoryKey = inspectRepository(
                port, cookie, workspaceRoot);
        JsonNode created = createWorkbench(
                port, cookie, workspaceRoot, repositoryKey);
        String workbenchId = created.path("workbenchId").asText();
        long initialVersion = created.path("version").asLong();
        JsonNode createdWorkbench = getJson(
                port, cookie, "/api/workbenches/" + workbenchId);
        JsonNode createdStage = createdWorkbench.path("stages").get(0);
        assertEquals("restart-recovery",
                createdStage.path("definitionIdentifier").asText());
        String stageInstanceIdentifier = createdStage.path(
                "stageInstanceIdentifier").asText();
        assertFalse(stageInstanceIdentifier.isEmpty());
        long runExpectedVersion = ensureStageConversation(
                port, cookie, workbenchId, stageInstanceIdentifier,
                initialVersion);
        JsonNode firstSubmission = submitWriteRun(
                port, cookie, workbenchId, stageInstanceIdentifier,
                runExpectedVersion, "restart-first", WAIT_MARKER
                        + " 启动写任务并等待服务进程中断");
        String firstRunId = firstSubmission.path("runId").asText();
        assertFalse(firstRunId.isEmpty());

        try {
            eventually("first Runtime handle and Workbench write lease persisted",
                    Duration.ofSeconds(30), () -> {
                        JsonNode workbench = getJson(
                                port, firstCookie,
                                "/api/workbenches/" + workbenchId);
                        return firstRunId.equals(
                                workbench.path("activeWriteRunId").asText())
                                && firstRunId.equals(sqliteString(
                                database,
                                "SELECT active_write_run_id FROM workbench WHERE id=?",
                                workbenchId))
                                && sqliteCount(
                                database,
                                "SELECT COUNT(*) FROM chat_run_runtime_handle WHERE run_id=?",
                                firstRunId) == 1
                                && invocationCount(invocationLog) == 1;
                    });
        } catch (AssertionError timeout) {
            throw new AssertionError(timeout.getMessage()
                    + "; safeFacts=" + safeActiveFacts(
                    port, firstCookie, database, invocationLog,
                    workbenchId, firstRunId), timeout);
        }
        JsonNode activeRun = getJson(
                port, cookie, runPath(workbenchId, firstRunId));
        assertEquals("RUNNING", activeRun.path("status").asText());
        assertEquals(1, invocationCount(invocationLog));

        crashApplication();

        assertEquals(1, sqliteCount(
                database,
                "SELECT COUNT(*) FROM chat_run_runtime_handle WHERE run_id=?",
                firstRunId),
                "crash must preserve the persisted handle for restart reconciliation");
        assertEquals(firstRunId, sqliteString(
                database,
                "SELECT active_write_run_id FROM workbench WHERE id=?",
                workbenchId));

        startApplication(projectRoot, fixture, database, runtimeRoot,
                port, "second");
        awaitReady(port);
        cookie = login(port);
        final String restartedCookie = cookie;
        eventually("startup recovery interrupts the unknown Runtime and releases lease",
                Duration.ofSeconds(30), () -> {
                    JsonNode recoveredRun = getJson(
                            port, restartedCookie,
                            runPath(workbenchId, firstRunId));
                    JsonNode recoveredWorkbench = getJson(
                            port, restartedCookie,
                            "/api/workbenches/" + workbenchId);
                    return "INTERRUPTED".equals(
                            recoveredRun.path("status").asText())
                            && "SERVER_RESTARTED".equals(
                            recoveredRun.path("failureCode").asText())
                            && recoveredWorkbench.path(
                            "activeWriteRunId").isNull()
                            && sqliteCount(
                            database,
                            "SELECT COUNT(*) FROM chat_run_runtime_handle WHERE run_id=?",
                            firstRunId) == 0;
                });

        JsonNode recoveredRun = getJson(
                port, cookie, runPath(workbenchId, firstRunId));
        assertEquals("INTERRUPTED", recoveredRun.path("status").asText());
        assertEquals("SERVER_RESTARTED",
                recoveredRun.path("failureCode").asText());
        assertTrue(recoveredRun.path("finishedAt").canConvertToLong());
        JsonNode recoveredWorkbench = getJson(
                port, cookie, "/api/workbenches/" + workbenchId);
        assertNull(jsonNullableText(
                recoveredWorkbench.get("activeWriteRunId")));
        assertNull(sqliteString(
                database,
                "SELECT active_write_run_id FROM workbench WHERE id=?",
                workbenchId));
        assertStageRunReleased(
                recoveredWorkbench, stageInstanceIdentifier);
        assertTrue(eventTypes(getJson(
                port, cookie,
                runPath(workbenchId, firstRunId)
                        + "/events-page?after=0&limit=200"))
                .contains("terminal"));
        assertEquals(1, invocationCount(invocationLog),
                "restart recovery must not replay the previous write Runtime");

        long recoveredVersion = recoveredWorkbench.path("version").asLong();
        JsonNode secondSubmission = submitWriteRun(
                port, cookie, workbenchId, stageInstanceIdentifier,
                recoveredVersion, "restart-second",
                "恢复完成后提交第二个写任务");
        String secondRunId = secondSubmission.path("runId").asText();
        assertFalse(secondRunId.isEmpty());
        assertFalse(secondSubmission.path("replayed").asBoolean());
        final String secondCookie = cookie;
        eventually("second write run reaches a terminal state",
                Duration.ofSeconds(30), () -> "SUCCEEDED".equals(
                        getJson(port, secondCookie,
                                runPath(workbenchId, secondRunId))
                                .path("status").asText()));
        assertEquals(2, invocationCount(invocationLog),
                "the released write lease must permit exactly one new Runtime execution");
        JsonNode afterSecond = getJson(
                port, cookie, "/api/workbenches/" + workbenchId);
        assertNull(jsonNullableText(afterSecond.get("activeWriteRunId")));
        assertStageRunReleased(afterSecond, stageInstanceIdentifier);
        assertTrue(Files.exists(repository.resolve("README.md")));
    }

    private void startApplication(
            Path projectRoot, Path fixture, Path database,
            Path runtimeRoot, int port,
            String attempt) throws IOException {
        assertNull(application, "previous application process must be stopped");
        applicationLog = temporaryDirectory.resolve(
                "application-" + attempt + ".log");
        List<String> command = new ArrayList<String>();
        command.add(Paths.get(System.getProperty("java.home"),
                "bin", "java").toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("com.example.agentweb.AgentWebApplication");
        command.add("--spring.profiles.active=e2e,e2e-linux,e2e-workbench");
        command.add("--server.port=" + port);
        command.add("--management.server.port=0");
        command.add("--spring.datasource.url=jdbc:sqlite:"
                + database.toAbsolutePath());
        command.add("--agent.public-access.bootstrap-admin-password="
                + ADMIN_PASSWORD);
        command.add("--agent.runtime.temp-root="
                + runtimeRoot.toAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(projectRoot.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(applicationLog.toFile());
        Map<String, String> environment = builder.environment();
        environment.put("GIT_CRED_ENC_KEY", fakeEncryptionKey());
        environment.put("AGENT_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD);
        environment.put("AGENT_E2E_ADMIN_PASSWORD", ADMIN_PASSWORD);
        environment.put("AGENT_E2E_WORKBENCH_RUNTIME_KEY",
                "deterministic-runtime-test-key");
        environment.put("AGENT_E2E_WORKBENCH_CODEX_COMMAND",
                fixture.toAbsolutePath().toString());
        application = builder.start();
    }

    private void awaitReady(int port) throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (application == null || !application.isAlive()) {
                throw new AssertionError(
                        "forked Spring application exited:\n"
                                + applicationLogTail());
            }
            try {
                if (request(port, "GET", "/api/auth/status",
                        null, null,
                        Collections.<String, String>emptyMap())
                        .status == 200) {
                    return;
                }
            } catch (IOException unavailable) {
                // Web server 尚未监听，继续受限轮询。
            }
            Thread.sleep(200L);
        }
        throw new AssertionError(
                "forked Spring application becomes ready timed out; application log:\n"
                        + applicationLogTail());
    }

    private String login(int port) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("username", "admin");
        body.put("password", ADMIN_PASSWORD);
        HttpResult result = request(
                port, "POST", "/api/auth/login", body, null,
                Collections.<String, String>emptyMap());
        assertEquals(200, result.status, result.body);
        assertNotNull(result.setCookie, "login must issue a session cookie");
        return result.setCookie.split(";", 2)[0];
    }

    private String inspectRepository(
            int port, String cookie, Path workspaceRoot) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("workspaceRoot", workspaceRoot.toAbsolutePath().toString());
        JsonNode response = requireJson(request(
                port, "POST", "/api/workbench/workspaces/inspect",
                body, cookie, Collections.<String, String>emptyMap()), 200);
        JsonNode repositories = response.path("repositories");
        assertEquals(1, repositories.size());
        assertEquals("restart-service",
                repositories.get(0).path("relativePath").asText());
        return repositories.get(0).path("repositoryKey").asText();
    }

    private JsonNode createWorkbench(
            int port, String cookie, Path workspaceRoot,
            String repositoryKey) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("title", "Runtime restart recovery process integration");
        body.put("originalGoal",
                "验证服务进程崩溃后写 Run 未知终态与租约恢复");
        body.put("agentType", "CODEX");
        body.put("environment", "process-integration");
        body.put("workspaceRoot", workspaceRoot.toAbsolutePath().toString());
        body.put("primaryRepository", repositoryKey);
        body.putArray("repositories").add(repositoryKey);
        body.putArray("stageDefinitionIdentifiers")
                .add("restart-recovery");
        body.put("expectedStageCatalogVersion", 2L);
        return requireJson(request(
                port, "POST", "/api/workbenches", body, cookie,
                header("Idempotency-Key", "restart-workbench-create")),
                201);
    }

    private void publishRestartRecoveryStage(
            int port, String cookie) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("definitionIdentifier", "restart-recovery");
        body.put("sequenceNumber", 10);
        body.put("displayName", "重启恢复");
        body.put("description", "验证 Stage Run 重启恢复");
        body.put("stageRules", "仅执行确定性的重启恢复测试任务。");
        body.putArray("allowedRunModes").add("MODIFY_WORKSPACE");
        body.putArray("commandReferences");
        body.putArray("skillReferences");
        body.putArray("mcpServerReferences");
        requireJson(request(
                port, "POST",
                "/api/admin-settings/workbench/stage-definitions",
                body, cookie, header("If-Match", "1")), 200);

        ObjectNode publish = MAPPER.createObjectNode();
        publish.put("expectedStageCatalogVersion", 1L);
        requireJson(request(
                port, "POST",
                "/api/admin-settings/workbench/stage-definitions/"
                        + "restart-recovery/publish",
                publish, cookie, header("If-Match", "1")), 200);
    }

    private long ensureStageConversation(
            int port, String cookie, String workbenchId,
            String stageInstanceIdentifier,
            long expectedVersion) throws Exception {
        JsonNode conversation = requireJson(request(
                port, "POST", "/api/workbenches/" + workbenchId
                        + "/stages/" + stageInstanceIdentifier
                        + "/conversation",
                null, cookie,
                header("If-Match", Long.toString(expectedVersion))), 200);
        assertFalse(conversation.path("sessionId").asText().isEmpty());
        assertEquals(0, conversation.path("generation").asInt());
        assertTrue(conversation.path("created").asBoolean());
        return conversation.path("workbenchVersion").asLong();
    }

    private JsonNode submitWriteRun(
            int port, String cookie, String workbenchId,
            String stageInstanceIdentifier, long expectedVersion,
            String idempotencyKey, String message) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("message", message);
        body.put("runMode", "MODIFY_WORKSPACE");
        body.set("attachments", MAPPER.createArrayNode());
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Idempotency-Key", idempotencyKey);
        headers.put("If-Match", Long.toString(expectedVersion));
        return requireJson(request(
                port, "POST", "/api/workbenches/" + workbenchId
                        + "/stages/" + stageInstanceIdentifier + "/runs",
                body, cookie, headers), 202);
    }

    private JsonNode getJson(
            int port, String cookie, String path) throws Exception {
        return requireJson(request(
                port, "GET", path, null, cookie,
                Collections.<String, String>emptyMap()), 200);
    }

    private HttpResult request(
            int port, String method, String path, JsonNode body,
            String cookie, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(1_000);
        connection.setReadTimeout(5_000);
        connection.setRequestProperty("Accept", "application/json");
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (body != null) {
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type", "application/json; charset=UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = stream == null ? "" : read(stream);
        String setCookie = connection.getHeaderField("Set-Cookie");
        connection.disconnect();
        return new HttpResult(status, responseBody, setCookie);
    }

    private JsonNode requireJson(HttpResult result, int expectedStatus)
            throws IOException {
        assertEquals(expectedStatus, result.status, result.body);
        return MAPPER.readTree(result.body);
    }

    private String read(InputStream stream) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    private void crashApplication() throws Exception {
        Process crashed = application;
        assertNotNull(crashed);
        application = null;
        crashed.destroyForcibly();
        assertTrue(crashed.waitFor(20L, TimeUnit.SECONDS),
                "forked application must terminate after forced crash");
    }

    private Path createGitRepository(
            Path workspaceRoot, String repositoryName) throws Exception {
        Files.createDirectories(workspaceRoot);
        Path repository = Files.createDirectory(
                workspaceRoot.resolve(repositoryName));
        git(repository, "init", "-q");
        git(repository, "config", "user.name", "Workbench Process E2E");
        git(repository, "config", "user.email",
                "workbench-process@example.invalid");
        Files.write(repository.resolve("README.md"),
                Collections.singletonList("# restart-service"),
                StandardCharsets.UTF_8);
        git(repository, "add", "README.md");
        git(repository, "commit", "-q", "-m", "initial fixture");
        return repository;
    }

    private void git(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        Collections.addAll(command, arguments);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).start();
        String output = read(process.getInputStream());
        assertTrue(process.waitFor(20L, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private int sqliteCount(
            Path database, String sql, String argument) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private String sqliteString(
            Path database, String sql, String argument) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private int invocationCount(Path invocationLog) throws IOException {
        return Files.exists(invocationLog)
                ? Files.readAllLines(
                invocationLog, StandardCharsets.UTF_8).size() : 0;
    }

    private String safeActiveFacts(
            int port, String cookie, Path database, Path invocationLog,
            String workbenchId, String runId) {
        try {
            JsonNode run = getJson(port, cookie,
                    runPath(workbenchId, runId));
            JsonNode workbench = getJson(
                    port, cookie, "/api/workbenches/" + workbenchId);
            String stageActiveRunId = null;
            for (JsonNode stage : workbench.path("stages")) {
                if (run.path("stageInstanceIdentifier").asText().equals(
                        stage.path("stageInstanceIdentifier").asText())) {
                    JsonNode activeRun = stage.get("activeRun");
                    stageActiveRunId = activeRun == null
                            || activeRun.isNull() ? null
                            : activeRun.path("runId").asText();
                    break;
                }
            }
            return "{runStatus=" + run.path("status").asText()
                    + ",failureCode="
                    + jsonNullableText(run.get("failureCode"))
                    + ",workbenchActiveWriteRunId="
                    + jsonNullableText(workbench.get("activeWriteRunId"))
                    + ",stageActiveRunId=" + stageActiveRunId
                    + ",persistedWriteRunId=" + sqliteString(
                    database,
                    "SELECT active_write_run_id FROM workbench WHERE id=?",
                    workbenchId)
                    + ",handleRows=" + sqliteCount(
                    database,
                    "SELECT COUNT(*) FROM chat_run_runtime_handle WHERE run_id=?",
                    runId)
                    + ",invocations=" + invocationCount(invocationLog)
                    + '}';
        } catch (Exception failure) {
            return "{unavailable="
                    + failure.getClass().getSimpleName() + '}';
        }
    }

    private void assertStageRunReleased(
            JsonNode workbench, String stageInstanceIdentifier) {
        for (JsonNode stage : workbench.path("stages")) {
            if (stageInstanceIdentifier.equals(
                    stage.path("stageInstanceIdentifier").asText())) {
                assertTrue(stage.path("activeRun").isNull());
                return;
            }
        }
        throw new AssertionError(
                "stage not found: " + stageInstanceIdentifier);
    }

    private List<String> eventTypes(JsonNode page) {
        List<String> result = new ArrayList<String>();
        for (JsonNode event : page.path("events")) {
            result.add(event.path("eventType").asText());
        }
        return result;
    }

    private String jsonNullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private String runPath(String workbenchId, String runId) {
        return "/api/workbenches/" + workbenchId + "/runs/" + runId;
    }

    private Map<String, String> header(String name, String value) {
        Map<String, String> result = new HashMap<String, String>();
        result.put(name, value);
        return result;
    }

    private String fakeEncryptionKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    private void eventually(
            String description, Duration timeout,
            CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.evaluate()) {
                    return;
                }
            } catch (Throwable failure) {
                lastFailure = failure;
            }
            Thread.sleep(200L);
        }
        AssertionError timeoutFailure = new AssertionError(
                description + " timed out; application log:\n"
                        + applicationLogTail());
        if (lastFailure != null) {
            timeoutFailure.initCause(lastFailure);
        }
        throw timeoutFailure;
    }

    private String applicationLogTail() throws IOException {
        if (applicationLog == null || !Files.exists(applicationLog)) {
            return "<application log unavailable>";
        }
        byte[] bytes = Files.readAllBytes(applicationLog);
        int start = Math.max(0, bytes.length - 20_000);
        return new String(bytes, start, bytes.length - start,
                StandardCharsets.UTF_8);
    }

    private static final class HttpResult {

        private final int status;
        private final String body;
        private final String setCookie;

        private HttpResult(int status, String body, String setCookie) {
            this.status = status;
            this.body = body;
            this.setCookie = setCookie;
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {

        boolean evaluate() throws Exception;
    }
}
