package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 不经 shell、带超时和输出上限的 Git 命令执行器。
 *
 * @author alex
 * @since 2026-08-01
 */
final class ProcessWorkspaceGitCommandRunner implements WorkspaceGitCommandRunner {

    private static final int BUFFER_SIZE = 8192;
    private static final long OUTPUT_CLOSE_WAIT_MILLIS = 1000L;

    private final Duration commandTimeout;
    private final int maximumOutputBytes;

    ProcessWorkspaceGitCommandRunner(Duration commandTimeout, int maximumOutputBytes) {
        if (commandTimeout == null || commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("Git command timeout must be positive");
        }
        if (maximumOutputBytes < 1) {
            throw new IllegalArgumentException("Git maximum output bytes must be positive");
        }
        this.commandTimeout = commandTimeout;
        this.maximumOutputBytes = maximumOutputBytes;
    }

    @Override
    public WorkspaceGitCommandResult execute(Path directory, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).start();
            AtomicReference<byte[]> output = new AtomicReference<byte[]>();
            AtomicReference<RuntimeException> readFailure = new AtomicReference<RuntimeException>();
            InputStream processOutput = process.getInputStream();
            Process runningProcess = process;
            Thread reader = new Thread(
                    () -> read(processOutput, output, readFailure, runningProcess),
                    "workbench-git-output-reader");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw failure("Git workspace command timed out", null);
            }
            reader.join(OUTPUT_CLOSE_WAIT_MILLIS);
            if (reader.isAlive()) {
                process.destroyForcibly();
                throw failure("Git workspace command output did not close", null);
            }
            if (readFailure.get() != null) {
                throw readFailure.get();
            }
            byte[] bytes = output.get() == null ? new byte[0] : output.get();
            return new WorkspaceGitCommandResult(process.exitValue(), bytes);
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure("Git workspace command could not be started", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw failure("Git workspace command was interrupted", ex);
        }
    }

    private void read(InputStream stream, AtomicReference<byte[]> output,
                      AtomicReference<RuntimeException> failure, Process process) {
        try {
            output.set(readBounded(stream));
        } catch (RuntimeException ex) {
            failure.set(ex);
            process.destroyForcibly();
        }
    }

    private byte[] readBounded(InputStream stream) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > maximumOutputBytes) {
                    throw failure("Git workspace command output exceeds configured limit", null);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw failure("Git workspace command output could not be read", ex);
        }
    }

    private WorkspaceOperationException failure(String message, Throwable cause) {
        return new WorkspaceOperationException(
                WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE, message, cause);
    }
}
