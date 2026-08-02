package com.example.agentweb.infra.runtime;

import com.example.agentweb.domain.runtime.RuntimeCommandPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本机 Codex execpolicy parser 验证生产规则文件的前置阻断语义。
 *
 * <p>该测试不启动 Agent、不读取登录态、不访问网络，只调用
 * {@code codex execpolicy check}；仍标记为 live，避免默认测试集依赖本机 CLI。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Tag("live")
class RuntimeExecPolicyCodexLiveTest {

    private static final int MAX_OUTPUT_BYTES = 65_536;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void blocksDirectAbsoluteWrappedCompoundAndAliasHighImpactCommands()
            throws Exception {
        Path isolatedHome = Files.createDirectory(tempDir.resolve("home"));
        Path policy = new RuntimeExecPolicyMaterializer(
                RuntimeCommandPolicy.platformDefault()).materialize(isolatedHome);

        assertForbidden(policy,
                Arrays.asList("git", "push", "origin", "master"));
        assertForbidden(policy,
                Arrays.asList(requireExecutable("git").toString(),
                        "push", "origin", "master"));
        if (File.separatorChar == '\\') {
            assertForbidden(policy,
                    Arrays.asList("cmd", "/c", "git push origin master"));
            assertForbidden(policy,
                    Arrays.asList("powershell", "-Command",
                            "git push origin master"));
        } else {
            assertForbidden(policy,
                    Arrays.asList("sh", "-lc", "git push origin master"));
            assertForbidden(policy,
                    Arrays.asList("bash", "-lc",
                            "echo ready && git push origin master"));
            assertForbidden(policy,
                    Arrays.asList("bash", "-lc",
                            "alias gpush='git push'; gpush origin master"));
        }
    }

    private void assertForbidden(Path policy, List<String> inspectedCommand)
            throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(codexCommand());
        command.add("execpolicy");
        command.add("check");
        command.add("--rules");
        command.add(policy.toString());
        command.add("--pretty");
        command.add("--");
        command.addAll(inspectedCommand);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(10L, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        assertTrue(exited, "codex execpolicy check timed out");
        String output = readBounded(process.getInputStream());
        assertEquals(0, process.exitValue(), output);
        JsonNode result = MAPPER.readTree(output);
        assertEquals("forbidden", result.path("decision").asText(), output);
    }

    private Path requireExecutable(String name) throws IOException {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(
                    java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (directory == null || directory.trim().isEmpty()) {
                    continue;
                }
                Path base = Paths.get(directory).toAbsolutePath().normalize();
                for (String suffix : Arrays.asList("", ".exe", ".cmd", ".bat")) {
                    Path candidate = base.resolve(name + suffix);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toAbsolutePath().normalize();
                    }
                }
            }
        }
        throw new IOException("required executable is unavailable: " + name);
    }

    private String codexCommand() {
        String configured = System.getenv("CODEX_CMD");
        return configured == null || configured.trim().isEmpty()
                ? "codex" : configured;
    }

    private String readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_OUTPUT_BYTES) {
                throw new IOException("codex execpolicy output exceeded limit");
            }
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
