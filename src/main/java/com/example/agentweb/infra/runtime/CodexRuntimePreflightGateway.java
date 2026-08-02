package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimePreflightErrorCode;
import com.example.agentweb.app.runtime.port.RuntimePreflightException;
import com.example.agentweb.app.runtime.port.RuntimePreflightGateway;
import com.example.agentweb.app.runtime.port.RuntimePreflightReport;
import com.example.agentweb.app.runtime.port.RuntimePreflightRequest;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.domain.shared.AgentType;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codex CLI 版本与兼容矩阵的公共 Runtime Preflight 适配器。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class CodexRuntimePreflightGateway
        implements RuntimePreflightGateway {

    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\."
                    + "(?:0|[1-9][0-9]*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private static final Pattern CODEX_VERSION_OUTPUT = Pattern.compile(
            "codex-cli[ \\t]+("
                    + SEMANTIC_VERSION.pattern() + ")");
    private static final char NEWLINE = '\n';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char NULL_CHARACTER = '\0';

    private final String codexCommand;
    private final CodexRuntimeCompatibilityMatrix compatibilityMatrix;
    private final RuntimeVersionProbeRunner probeRunner;

    public CodexRuntimePreflightGateway(
            String codexCommand,
            CodexRuntimeCompatibilityMatrix compatibilityMatrix,
            Duration probeTimeout, long maximumProbeOutputBytes) {
        this(codexCommand, compatibilityMatrix,
                new ProcessRuntimeVersionProbeRunner(
                        probeTimeout, maximumProbeOutputBytes));
    }

    CodexRuntimePreflightGateway(
            String codexCommand,
            CodexRuntimeCompatibilityMatrix compatibilityMatrix,
            RuntimeVersionProbeRunner probeRunner) {
        this.codexCommand = requireSafeExecutable(codexCommand);
        if (compatibilityMatrix == null || probeRunner == null) {
            throw new IllegalArgumentException(
                    "Codex compatibility matrix and probe runner are required");
        }
        this.compatibilityMatrix = compatibilityMatrix;
        this.probeRunner = probeRunner;
    }

    @Override
    public RuntimePreflightReport inspect(RuntimePreflightRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "runtime preflight request must not be null");
        }
        RuntimeSelection selection = request.getRuntimeSelection();
        if (selection.getAgentType() != AgentType.CODEX) {
            throw failure(
                    RuntimePreflightErrorCode.RUNTIME_UNSUPPORTED,
                    "Runtime provider is not supported by the Codex preflight adapter");
        }
        WorkspaceLayout layout = request.getWorkspaceLayout();
        compatibilityMatrix.requireCompatible(
                request.getCapabilityBinding().getRuntimeCompatibility(), layout);
        RuntimeVersionProbeResult probe = probeRunner.run(
                Collections.unmodifiableList(
                        Arrays.asList(codexCommand, "--version")));
        if (probe.getExitCode() != 0) {
            throw failure(
                    RuntimePreflightErrorCode.RUNTIME_PROBE_FAILED,
                    "Runtime version probe exited unsuccessfully");
        }
        String runtimeVersion = parseVersion(probe.getOutput());
        RuntimeVersionPolicy versionPolicy =
                selection.getRuntimeVersionPolicy();
        if (versionPolicy.getMode() == RuntimeVersionPolicy.Mode.EXACT
                && !versionPolicy.exactVersion().orElse("")
                .equals(runtimeVersion)) {
            throw failure(
                    RuntimePreflightErrorCode.RUNTIME_VERSION_MISMATCH,
                    "Runtime version does not match the exact version policy");
        }
        return new RuntimePreflightReport(
                AgentType.CODEX, runtimeVersion,
                compatibilityMatrix.getMatrixId(), layout.getSandboxMode(),
                layout.getReadableRoots().size(),
                layout.getWritableRoots().size(),
                request.getCapabilityBinding().getBindingHash());
    }

    private String parseVersion(String output) {
        Matcher matcher = CODEX_VERSION_OUTPUT.matcher(output.trim());
        if (!matcher.matches()) {
            throw failure(
                    RuntimePreflightErrorCode.RUNTIME_VERSION_MALFORMED,
                    "Runtime version output is not recognized");
        }
        return matcher.group(1);
    }

    private static String requireSafeExecutable(String value) {
        if (value == null || value.trim().isEmpty()
                || value.indexOf(NEWLINE) >= 0
                || value.indexOf(CARRIAGE_RETURN) >= 0
                || value.indexOf(NULL_CHARACTER) >= 0) {
            throw new IllegalArgumentException(
                    "Codex preflight executable is unsafe");
        }
        return value;
    }

    private RuntimePreflightException failure(
            RuntimePreflightErrorCode code, String message) {
        return new RuntimePreflightException(code, message);
    }
}
