package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.CredentialReference;
import com.example.agentweb.app.runtime.port.RuntimePreflightErrorCode;
import com.example.agentweb.app.runtime.port.RuntimePreflightException;
import com.example.agentweb.app.runtime.port.RuntimePreflightReport;
import com.example.agentweb.app.runtime.port.RuntimePreflightRequest;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codex Runtime Preflight 的版本、兼容矩阵和 Workspace Layout 契约测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class CodexRuntimePreflightGatewayTest {

    private static final String MATRIX_ID = "CODEX_WORKBENCH@1";
    private static final String VERIFIED_VERSION = "0.145.0";

    @TempDir
    Path tempDir;

    @Test
    void configuredVersionShouldUseFixedProbeTokensAndReturnSafeMultiRepositoryReport() {
        RecordingProbeRunner runner = successfulRunner(VERIFIED_VERSION);
        CodexRuntimePreflightGateway gateway = gateway(runner, fullMatrix());
        RuntimePreflightRequest request = request(
                AgentType.CODEX, RuntimeVersionPolicy.configured(),
                SandboxMode.READ_ONLY, true, MATRIX_ID);

        RuntimePreflightReport report = gateway.inspect(request);

        assertEquals(Arrays.asList("codex", "--version"), runner.command);
        assertEquals(1, runner.calls);
        assertEquals(AgentType.CODEX, report.getAgentType());
        assertEquals(VERIFIED_VERSION, report.getRuntimeVersion());
        assertEquals(MATRIX_ID, report.getCompatibilityMatrixId());
        assertEquals(SandboxMode.READ_ONLY, report.getSandboxMode());
        assertEquals(2, report.getReadableRootCount());
        assertEquals(0, report.getWritableRootCount());
        assertTrue(report.isMultiRepository());
        assertEquals(request.getCapabilityBinding().getBindingHash(),
                report.getCapabilityBindingHash());
        assertFalse(report.toString().contains(tempDir.toAbsolutePath().toString()));
    }

    @Test
    void exactVersionShouldRequireExactVerifiedRuntimeVersion() {
        CodexRuntimePreflightGateway matching = gateway(
                successfulRunner(VERIFIED_VERSION), fullMatrix());

        RuntimePreflightReport report = matching.inspect(request(
                AgentType.CODEX, RuntimeVersionPolicy.exact(VERIFIED_VERSION),
                SandboxMode.READ_ONLY, false, MATRIX_ID));

        assertEquals(VERIFIED_VERSION, report.getRuntimeVersion());

        RuntimePreflightException mismatch = assertThrows(
                RuntimePreflightException.class, () -> gateway(
                        successfulRunner(VERIFIED_VERSION), fullMatrix()).inspect(request(
                        AgentType.CODEX, RuntimeVersionPolicy.exact("0.144.0"),
                        SandboxMode.READ_ONLY, false, MATRIX_ID)));
        assertEquals(RuntimePreflightErrorCode.RUNTIME_VERSION_MISMATCH,
                mismatch.getErrorCode());
    }

    @Test
    void configuredVersionShouldRejectVersionsOutsideConstructorAllowlist() {
        RuntimePreflightException failure = assertThrows(
                RuntimePreflightException.class, () -> gateway(
                        successfulRunner("0.146.0"), fullMatrix()).inspect(request(
                        AgentType.CODEX, RuntimeVersionPolicy.configured(),
                        SandboxMode.READ_ONLY, false, MATRIX_ID)));

        assertEquals(RuntimePreflightErrorCode.RUNTIME_VERSION_NOT_ALLOWED,
                failure.getErrorCode());
    }

    @Test
    void versionOutputParsingShouldRejectAdditionalLinesAndProviderNoise() {
        RecordingProbeRunner runner = new RecordingProbeRunner(
                new RuntimeVersionProbeResult(
                        0, "codex-cli 0.145.0\nuntrusted provider output"));

        RuntimePreflightException failure = assertThrows(
                RuntimePreflightException.class, () -> gateway(
                        runner, fullMatrix()).inspect(request(
                        AgentType.CODEX, RuntimeVersionPolicy.configured(),
                        SandboxMode.READ_ONLY, false, MATRIX_ID)));

        assertEquals(RuntimePreflightErrorCode.RUNTIME_VERSION_MALFORMED,
                failure.getErrorCode());
        assertFalse(failure.getMessage().contains("untrusted provider output"));

        RuntimePreflightException invalidSemanticVersion = assertThrows(
                RuntimePreflightException.class, () -> gateway(
                        new RecordingProbeRunner(new RuntimeVersionProbeResult(
                                0, "codex-cli 00.145.0")),
                        fullMatrix()).inspect(request(
                        AgentType.CODEX, RuntimeVersionPolicy.configured(),
                        SandboxMode.READ_ONLY, false, MATRIX_ID)));
        assertEquals(RuntimePreflightErrorCode.RUNTIME_VERSION_MALFORMED,
                invalidSemanticVersion.getErrorCode());
    }

    @Test
    void bindingCompatibilityMatrixMismatchShouldFailBeforeStartingProbe() {
        RecordingProbeRunner runner = successfulRunner(VERIFIED_VERSION);

        RuntimePreflightException failure = assertThrows(
                RuntimePreflightException.class, () -> gateway(
                        runner, fullMatrix()).inspect(request(
                        AgentType.CODEX, RuntimeVersionPolicy.configured(),
                        SandboxMode.READ_ONLY, false, "CODEX_OTHER@1")));

        assertEquals(RuntimePreflightErrorCode.RUNTIME_COMPATIBILITY_MISMATCH,
                failure.getErrorCode());
        assertEquals(0, runner.calls);
    }

    @Test
    void unsupportedSandboxOrMultiRepositoryLayoutShouldFailBeforeProbe() {
        CodexRuntimeCompatibilityMatrix readOnlySingleRepository =
                new CodexRuntimeCompatibilityMatrix(
                        MATRIX_ID, EnumSet.of(SandboxMode.READ_ONLY), false);
        RecordingProbeRunner writeRunner = successfulRunner(VERIFIED_VERSION);

        RuntimePreflightException writeFailure = assertThrows(
                RuntimePreflightException.class, () -> gateway(
                        writeRunner, readOnlySingleRepository).inspect(request(
                        AgentType.CODEX, RuntimeVersionPolicy.configured(),
                        SandboxMode.WORKSPACE_WRITE, false, MATRIX_ID)));

        assertEquals(RuntimePreflightErrorCode.RUNTIME_LAYOUT_UNSUPPORTED,
                writeFailure.getErrorCode());
        assertEquals(0, writeRunner.calls);

        RecordingProbeRunner multiRunner = successfulRunner(VERIFIED_VERSION);
        RuntimePreflightException multiFailure = assertThrows(
                RuntimePreflightException.class, () -> gateway(
                        multiRunner, readOnlySingleRepository).inspect(request(
                        AgentType.CODEX, RuntimeVersionPolicy.configured(),
                        SandboxMode.READ_ONLY, true, MATRIX_ID)));
        assertEquals(RuntimePreflightErrorCode.RUNTIME_LAYOUT_UNSUPPORTED,
                multiFailure.getErrorCode());
        assertEquals(0, multiRunner.calls);
    }

    @Test
    void probeFailureShouldExposeOnlyStableCodeAndSafeMessage() {
        RecordingProbeRunner runner = new RecordingProbeRunner(
                new RuntimeVersionProbeResult(
                        17, "secret stderr /private/workspace/repository"));

        RuntimePreflightException failure = assertThrows(
                RuntimePreflightException.class, () -> gateway(
                        runner, fullMatrix()).inspect(request(
                        AgentType.CODEX, RuntimeVersionPolicy.configured(),
                        SandboxMode.READ_ONLY, false, MATRIX_ID)));

        assertEquals(RuntimePreflightErrorCode.RUNTIME_PROBE_FAILED,
                failure.getErrorCode());
        assertFalse(failure.getMessage().contains("secret stderr"));
        assertFalse(failure.getMessage().contains("/private/workspace"));
    }

    @Test
    void nonCodexRuntimeShouldFailClosedWithoutStartingProbe() {
        RecordingProbeRunner runner = successfulRunner(VERIFIED_VERSION);

        RuntimePreflightException failure = assertThrows(
                RuntimePreflightException.class, () -> gateway(
                        runner, fullMatrix()).inspect(request(
                        AgentType.CLAUDE, RuntimeVersionPolicy.configured(),
                        SandboxMode.READ_ONLY, false, MATRIX_ID)));

        assertEquals(RuntimePreflightErrorCode.RUNTIME_UNSUPPORTED,
                failure.getErrorCode());
        assertEquals(0, runner.calls);
    }

    @Test
    void constructorShouldRejectEmptyVersionAllowlistAndIncompleteMatrix() {
        RecordingProbeRunner runner = successfulRunner(VERIFIED_VERSION);

        assertThrows(IllegalArgumentException.class,
                () -> new CodexRuntimePreflightGateway(
                        "codex", Collections.<String>emptySet(),
                        fullMatrix(), runner));
        assertThrows(IllegalArgumentException.class,
                () -> new CodexRuntimeCompatibilityMatrix(
                        MATRIX_ID, Collections.<SandboxMode>emptySet(), true));
    }

    private CodexRuntimePreflightGateway gateway(
            RuntimeVersionProbeRunner runner,
            CodexRuntimeCompatibilityMatrix matrix) {
        return new CodexRuntimePreflightGateway(
                "codex", new LinkedHashSet<String>(
                Collections.singletonList(VERIFIED_VERSION)),
                matrix, runner);
    }

    private CodexRuntimeCompatibilityMatrix fullMatrix() {
        return new CodexRuntimeCompatibilityMatrix(
                MATRIX_ID, EnumSet.allOf(SandboxMode.class), true);
    }

    private RecordingProbeRunner successfulRunner(String version) {
        return new RecordingProbeRunner(
                new RuntimeVersionProbeResult(0, "codex-cli " + version + "\n"));
    }

    private RuntimePreflightRequest request(
            AgentType agentType, RuntimeVersionPolicy versionPolicy,
            SandboxMode sandboxMode, boolean multiRepository,
            String runtimeCompatibility) {
        Path primary = tempDir.resolve("primary").toAbsolutePath().normalize();
        List<String> readable = new ArrayList<String>();
        readable.add(primary.toString());
        if (multiRepository) {
            readable.add(tempDir.resolve("additional")
                    .toAbsolutePath().normalize().toString());
        }
        List<String> writable = sandboxMode == SandboxMode.READ_ONLY
                ? Collections.<String>emptyList()
                : new ArrayList<String>(readable);
        return new RuntimePreflightRequest(
                new RuntimeSelection(
                        agentType, versionPolicy,
                        CredentialReference.systemConfiguration()),
                new WorkspaceLayout(
                        primary.toString(), readable, writable, sandboxMode),
                binding(runtimeCompatibility));
    }

    private ResolvedCapabilityBinding binding(String runtimeCompatibility) {
        return ResolvedCapabilityBinding.resolve(
                "policy@1", "profile", "1.0.0",
                CanonicalHashing.sha256("profile"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                runtimeCompatibility);
    }

    private static final class RecordingProbeRunner
            implements RuntimeVersionProbeRunner {
        private final RuntimeVersionProbeResult result;
        private List<String> command = Collections.emptyList();
        private int calls;

        private RecordingProbeRunner(RuntimeVersionProbeResult result) {
            this.result = result;
        }

        @Override
        public RuntimeVersionProbeResult run(List<String> command) {
            this.command = new ArrayList<String>(command);
            calls++;
            return result;
        }
    }
}
