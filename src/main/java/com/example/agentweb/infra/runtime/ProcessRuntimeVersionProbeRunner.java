package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimePreflightErrorCode;
import com.example.agentweb.app.runtime.port.RuntimePreflightException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 使用 ProcessBuilder token 直接执行、有界采集输出的版本探测 Runner。
 *
 * @author alex
 * @since 2026-08-01
 */
final class ProcessRuntimeVersionProbeRunner
        implements RuntimeVersionProbeRunner {

    private static final long POLL_MILLIS = 10L;
    private static final long TERMINATION_WAIT_MILLIS = 100L;
    private static final long MAXIMUM_IN_MEMORY_OUTPUT_BYTES = 1024L * 1024L;
    private static final char NEWLINE = '\n';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char NULL_CHARACTER = '\0';

    private final long timeoutNanos;
    private final long maximumOutputBytes;

    ProcessRuntimeVersionProbeRunner(
            Duration timeout, long maximumOutputBytes) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "runtime version probe timeout must be positive");
        }
        if (maximumOutputBytes < 1L
                || maximumOutputBytes > MAXIMUM_IN_MEMORY_OUTPUT_BYTES) {
            throw new IllegalArgumentException(
                    "runtime version probe output bound is invalid");
        }
        this.timeoutNanos = timeout.toNanos();
        this.maximumOutputBytes = maximumOutputBytes;
    }

    @Override
    public RuntimeVersionProbeResult run(List<String> command) {
        requireFixedCommand(command);
        Path output = null;
        Process process = null;
        boolean started = false;
        try {
            output = Files.createTempFile(
                    "agent-runtime-version-", ".output");
            ProcessBuilder builder = new ProcessBuilder(
                    new ArrayList<String>(command));
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            process = builder.start();
            started = true;
            await(process, output);
            long outputSize = Files.size(output);
            if (outputSize > maximumOutputBytes) {
                throw failure(
                        RuntimePreflightErrorCode
                                .RUNTIME_PROBE_OUTPUT_LIMIT_EXCEEDED,
                        "Runtime version probe exceeded its output limit");
            }
            byte[] bytes = Files.readAllBytes(output);
            return new RuntimeVersionProbeResult(
                    process.exitValue(),
                    new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimePreflightException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RuntimePreflightException(
                    RuntimePreflightErrorCode.RUNTIME_PROBE_INTERRUPTED,
                    "Runtime version probe was interrupted", failure);
        } catch (IOException failure) {
            RuntimePreflightErrorCode code = started
                    ? RuntimePreflightErrorCode.RUNTIME_PROBE_FAILED
                    : RuntimePreflightErrorCode.RUNTIME_PROBE_START_FAILED;
            throw new RuntimePreflightException(
                    code, "Runtime version probe could not complete");
        } finally {
            terminate(process);
            delete(output);
        }
    }

    private void await(Process process, Path output)
            throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (Files.size(output) > maximumOutputBytes) {
                terminate(process);
                throw failure(
                        RuntimePreflightErrorCode
                                .RUNTIME_PROBE_OUTPUT_LIMIT_EXCEEDED,
                        "Runtime version probe exceeded its output limit");
            }
            if (System.nanoTime() - startedAt >= timeoutNanos) {
                terminate(process);
                throw failure(
                        RuntimePreflightErrorCode.RUNTIME_PROBE_TIMEOUT,
                        "Runtime version probe timed out");
            }
        }
        if (System.nanoTime() - startedAt >= timeoutNanos) {
            throw failure(
                    RuntimePreflightErrorCode.RUNTIME_PROBE_TIMEOUT,
                    "Runtime version probe timed out");
        }
    }

    private void requireFixedCommand(List<String> command) {
        if (command == null || command.size() != 2
                || !"--version".equals(command.get(1))) {
            throw new IllegalArgumentException(
                    "runtime version probe command must contain executable and --version");
        }
        String executable = command.get(0);
        if (executable == null || executable.trim().isEmpty()
                || executable.indexOf(NEWLINE) >= 0
                || executable.indexOf(CARRIAGE_RETURN) >= 0
                || executable.indexOf(NULL_CHARACTER) >= 0) {
            throw new IllegalArgumentException(
                    "runtime version probe executable is unsafe");
        }
    }

    private void terminate(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(
                    TERMINATION_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(
                        TERMINATION_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void delete(Path output) {
        if (output == null) {
            return;
        }
        try {
            Files.deleteIfExists(output);
        } catch (IOException ignored) {
            // 临时文件路径和内容不得进入公开错误；由主机临时目录治理兜底。
        }
    }

    private RuntimePreflightException failure(
            RuntimePreflightErrorCode code, String safeMessage) {
        return new RuntimePreflightException(code, safeMessage);
    }
}
