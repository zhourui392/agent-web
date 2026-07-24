package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.port.DeploymentExecutionSpec;
import com.example.agentweb.app.harness.port.DeploymentGateway;
import com.example.agentweb.config.harness.HarnessDeploymentProperties;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.DeploymentCommand;
import com.example.agentweb.domain.harness.DeploymentOutcome;
import com.example.agentweb.domain.harness.DeploymentStep;
import com.example.agentweb.domain.harness.DeploymentStepResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * tokenized 管理员模板的 local 进程适配器；失败停止且不自动执行 ROLLBACK。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class ProcessDeploymentGateway implements DeploymentGateway {

    private static final List<DeploymentStep> EXECUTED_STEPS = Arrays.asList(
            DeploymentStep.BUILD, DeploymentStep.DEPLOY,
            DeploymentStep.HEALTH_CHECK, DeploymentStep.ACCEPTANCE);
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(api[_-]?key|secret|token|password)([\\s:=]+)[^\\s]+"
    );
    private static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"
    );

    private final HarnessDeploymentProperties properties;
    private final Clock clock;

    public ProcessDeploymentGateway(HarnessDeploymentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public DeploymentOutcome execute(DeploymentExecutionSpec spec) {
        if (spec == null || properties.getCommandTimeoutSeconds() < 1L
                || properties.getMaxOutputBytes() < 1L) {
            throw new IllegalArgumentException("deployment process bounds and spec are required");
        }
        Path workingDir = workingDirectory(spec.getWorkingDir());
        List<DeploymentStepResult> results = new ArrayList<DeploymentStepResult>();
        for (DeploymentStep step : EXECUTED_STEPS) {
            DeploymentStepResult result = executeStep(
                    spec.getTemplate().command(step), workingDir);
            results.add(result);
            if (!result.passed()) {
                break;
            }
        }
        return new DeploymentOutcome(results);
    }

    private DeploymentStepResult executeStep(DeploymentCommand command, Path workingDir) {
        Path output = null;
        Process process = null;
        Instant startedAt = clock.instant();
        try {
            output = Files.createTempFile("harness-deployment-", ".log");
            ProcessBuilder builder = new ProcessBuilder(command.getArguments());
            builder.directory(workingDir.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            applyMinimalEnvironment(builder);
            process = builder.start();
            int exitCode = await(process, output);
            byte[] raw = boundedBytes(output);
            String redacted = redact(new String(raw, StandardCharsets.UTF_8));
            byte[] evidence = redacted.getBytes(StandardCharsets.UTF_8);
            String summary = redacted.trim().isEmpty() ? "no output" : redacted.trim();
            if (summary.length() > 4000) {
                summary = summary.substring(0, 4000);
            }
            return new DeploymentStepResult(command.getStep(), exitCode,
                    ArtifactContent.from(evidence).getSha256(), summary,
                    startedAt, clock.instant());
        } catch (IOException ex) {
            throw new IllegalStateException("deployment command could not be executed", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // 临时证据删除失败不暴露物理路径；服务账户应定期清理系统 temp。
                }
            }
        }
    }

    private int await(Process process, Path output) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(properties.getCommandTimeoutSeconds());
        try {
            while (process.isAlive()) {
                if (Files.size(output) > properties.getMaxOutputBytes()) {
                    process.destroyForcibly();
                    process.waitFor(1L, TimeUnit.SECONDS);
                    return -3;
                }
                if (System.nanoTime() >= deadline) {
                    process.destroyForcibly();
                    process.waitFor(1L, TimeUnit.SECONDS);
                    return -2;
                }
                Thread.sleep(20L);
            }
            return process.exitValue();
        } catch (IOException ex) {
            throw new IllegalStateException("deployment output could not be inspected", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("deployment command was interrupted", ex);
        }
    }

    private byte[] boundedBytes(Path output) throws IOException {
        byte[] bytes = Files.readAllBytes(output);
        int length = (int) Math.min((long) bytes.length, properties.getMaxOutputBytes());
        return Arrays.copyOf(bytes, length);
    }

    private Path workingDirectory(String value) {
        try {
            Path path = Paths.get(value).toRealPath();
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("deployment working directory is unavailable");
            }
            return path;
        } catch (IOException ex) {
            throw new IllegalStateException("deployment working directory is unavailable", ex);
        }
    }

    private void applyMinimalEnvironment(ProcessBuilder builder) {
        Map<String, String> inherited = new HashMap<String, String>(System.getenv());
        Map<String, String> environment = builder.environment();
        environment.clear();
        String path = inherited.get("PATH");
        if (path != null) {
            environment.put("PATH", path);
        }
    }

    private String redact(String value) {
        String named = NAMED_SECRET.matcher(value).replaceAll("$1$2[REDACTED]");
        return JWT.matcher(named).replaceAll("[REDACTED_JWT]");
    }
}
