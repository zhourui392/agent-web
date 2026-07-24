package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.harness.ChangedFileEvidence;
import com.example.agentweb.domain.harness.HarnessHashing;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.domain.harness.WorkspaceChangeEvidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过只读 Git 命令捕获创建时工作区基线；任何不确定结果都失败关闭。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class ProcessWorkspaceBaselineGateway implements WorkspaceBaselineGateway {

    private static final int COMMAND_TIMEOUT_SECONDS = 10;
    private static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;

    private final Clock clock;

    public ProcessWorkspaceBaselineGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public WorkspaceBaseline capture(String workingDir) {
        try {
            Path working = Paths.get(workingDir).toRealPath();
            Path root = Paths.get(requiredText(execute(working,
                    "git", "rev-parse", "--show-toplevel"), "repository root")).toRealPath();
            if (!working.startsWith(root)) {
                throw new WorkspaceBaselineCaptureException(
                        "working directory is outside detected Git repository");
            }
            String branch = optionalText(executeAllowFailure(root,
                    "git", "symbolic-ref", "--short", "-q", "HEAD"), "DETACHED");
            String head = requiredText(execute(root, "git", "rev-parse", "HEAD"), "Git HEAD");
            byte[] status = execute(root, "git", "status", "--porcelain=v1", "-z",
                    "--untracked-files=all", "--no-renames").output;
            byte[] diff = execute(root, "git", "diff", "--binary", "HEAD", "--").output;
            List<String> untracked = zeroSeparated(execute(root, "git", "ls-files",
                    "--others", "--exclude-standard", "-z").output);
            String diffHash = fingerprint(root, status, diff, untracked);
            List<ChangedFileEvidence> files = changedFiles(root, status);
            return WorkspaceBaseline.capture(root.toString(), branch, head,
                    status.length == 0, diffHash, files, clock.instant());
        } catch (WorkspaceBaselineCaptureException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new WorkspaceBaselineCaptureException("could not capture Git workspace baseline", ex);
        }
    }

    @Override
    public WorkspaceChangeEvidence captureChanges(String workingDir,
                                                  WorkspaceBaseline baseline) {
        if (baseline == null) {
            throw new WorkspaceBaselineCaptureException(
                    "implementation baseline is required for change evidence");
        }
        WorkspaceBaseline current = capture(workingDir);
        if (!baseline.belongsToSameRepository(current)) {
            throw new WorkspaceBaselineCaptureException(
                    "implementation baseline repository or branch changed");
        }
        return new WorkspaceChangeEvidence(baseline, current);
    }

    private List<ChangedFileEvidence> changedFiles(Path root, byte[] statusOutput)
            throws IOException {
        List<ChangedFileEvidence> files = new ArrayList<ChangedFileEvidence>();
        for (String entry : zeroSeparated(statusOutput)) {
            if (entry.length() < 4 || entry.charAt(2) != ' ') {
                throw new WorkspaceBaselineCaptureException("Git status entry is malformed");
            }
            String status = entry.substring(0, 2).trim();
            String relative = entry.substring(3);
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root)) {
                throw new WorkspaceBaselineCaptureException(
                        "Git status entry escapes repository root");
            }
            byte[] state = "??".equals(status)
                    ? untrackedState(file) : trackedState(root, relative);
            files.add(ChangedFileEvidence.observed(relative, status,
                    stateFingerprint(status, state)));
        }
        return files;
    }

    private byte[] untrackedState(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
            throw new WorkspaceBaselineCaptureException(
                    "untracked workspace entry is not a regular file");
        }
        if (Files.size(file) > MAX_OUTPUT_BYTES) {
            throw new WorkspaceBaselineCaptureException(
                    "untracked workspace file exceeds baseline size limit");
        }
        return Files.readAllBytes(file);
    }

    private byte[] trackedState(Path root, String relative) {
        return execute(root, "git", "diff", "--binary", "HEAD", "--", relative).output;
    }

    private String stateFingerprint(String status, byte[] state) {
        return HarnessHashing.sha256((status + ':' + HarnessHashing.sha256(state))
                .getBytes(StandardCharsets.UTF_8));
    }

    private String fingerprint(Path root, byte[] status, byte[] diff,
                               List<String> untracked) throws IOException {
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        framed(canonical, "status", status);
        framed(canonical, "diff", diff);
        List<String> sorted = new ArrayList<String>(untracked);
        Collections.sort(sorted);
        for (String relative : sorted) {
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                throw new WorkspaceBaselineCaptureException(
                        "untracked workspace entry is not a regular in-repository file");
            }
            long size = Files.size(file);
            if (size > MAX_OUTPUT_BYTES) {
                throw new WorkspaceBaselineCaptureException(
                        "untracked workspace file exceeds baseline size limit");
            }
            byte[] content = Files.readAllBytes(file);
            framed(canonical, "untracked-path", relative.getBytes(StandardCharsets.UTF_8));
            framed(canonical, "untracked-hash",
                    HarnessHashing.sha256(content).getBytes(StandardCharsets.UTF_8));
        }
        return HarnessHashing.sha256(canonical.toByteArray());
    }

    private void framed(ByteArrayOutputStream target, String name, byte[] value) throws IOException {
        target.write(name.getBytes(StandardCharsets.UTF_8));
        target.write(':');
        target.write(String.valueOf(value.length).getBytes(StandardCharsets.UTF_8));
        target.write(':');
        target.write(value);
        target.write('\n');
    }

    private CommandResult execute(Path directory, String... command) {
        CommandResult result = executeAllowFailure(directory, command);
        if (result.exitCode != 0) {
            throw new WorkspaceBaselineCaptureException(
                    "required Git workspace inspection command failed");
        }
        return result;
    }

    private CommandResult executeAllowFailure(Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).start();
            AtomicReference<byte[]> output = new AtomicReference<byte[]>();
            AtomicReference<RuntimeException> readFailure = new AtomicReference<RuntimeException>();
            Thread reader = new Thread(() -> {
                try {
                    output.set(readBounded(process.getInputStream()));
                } catch (RuntimeException ex) {
                    readFailure.set(ex);
                }
            }, "harness-git-baseline-reader");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new WorkspaceBaselineCaptureException("Git workspace inspection timed out");
            }
            reader.join(1000L);
            if (reader.isAlive()) {
                throw new WorkspaceBaselineCaptureException("Git workspace inspection output did not close");
            }
            if (readFailure.get() != null) {
                throw readFailure.get();
            }
            return new CommandResult(process.exitValue(),
                    output.get() == null ? new byte[0] : output.get());
        } catch (IOException ex) {
            throw new WorkspaceBaselineCaptureException("could not start Git workspace inspection", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new WorkspaceBaselineCaptureException("Git workspace inspection was interrupted", ex);
        }
    }

    private byte[] readBounded(InputStream stream) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > MAX_OUTPUT_BYTES) {
                    throw new WorkspaceBaselineCaptureException(
                            "Git workspace inspection output exceeds size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new WorkspaceBaselineCaptureException(
                    "could not read Git workspace inspection output", ex);
        }
    }

    private String requiredText(CommandResult result, String name) {
        String value = new String(result.output, StandardCharsets.UTF_8).trim();
        if (value.isEmpty()) {
            throw new WorkspaceBaselineCaptureException(name + " is empty");
        }
        return value;
    }

    private String optionalText(CommandResult result, String fallback) {
        if (result.exitCode != 0) {
            return fallback;
        }
        String value = new String(result.output, StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? fallback : value;
    }

    private List<String> zeroSeparated(byte[] value) {
        List<String> entries = new ArrayList<String>();
        int start = 0;
        for (int index = 0; index < value.length; index++) {
            if (value[index] == 0) {
                if (index > start) {
                    entries.add(new String(value, start, index - start, StandardCharsets.UTF_8));
                }
                start = index + 1;
            }
        }
        if (start < value.length) {
            entries.add(new String(value, start, value.length - start, StandardCharsets.UTF_8));
        }
        return entries;
    }

    private static final class CommandResult {
        private final int exitCode;
        private final byte[] output;

        private CommandResult(int exitCode, byte[] output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
