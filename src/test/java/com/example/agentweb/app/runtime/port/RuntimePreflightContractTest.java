package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中性 Runtime Preflight 请求、报告与稳定错误合同测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimePreflightContractTest {

    @Test
    void requestShouldRequireAndExposeOnlyNeutralRuntimeFacts() {
        RuntimeSelection selection = new RuntimeSelection(
                AgentType.CODEX, RuntimeVersionPolicy.configured(),
                CredentialReference.systemConfiguration());
        WorkspaceLayout layout = new WorkspaceLayout(
                "/workspace/primary",
                Collections.singletonList("/workspace/primary"),
                Collections.<String>emptyList(), SandboxMode.READ_ONLY);
        ResolvedCapabilityBinding binding = binding();

        RuntimePreflightRequest request = new RuntimePreflightRequest(
                selection, layout, binding);

        assertEquals(selection, request.getRuntimeSelection());
        assertEquals(layout, request.getWorkspaceLayout());
        assertEquals(binding, request.getCapabilityBinding());
        assertThrows(NullPointerException.class,
                () -> new RuntimePreflightRequest(null, layout, binding));
        assertThrows(NullPointerException.class,
                () -> new RuntimePreflightRequest(selection, null, binding));
        assertThrows(NullPointerException.class,
                () -> new RuntimePreflightRequest(selection, layout, null));
    }

    @Test
    void reportShouldExposeCountsWithoutLeakingRepositoryRoots() {
        RuntimePreflightReport report = new RuntimePreflightReport(
                AgentType.CODEX, "0.145.0", "CODEX_WORKBENCH@1",
                SandboxMode.WORKSPACE_WRITE, 2, 2,
                CanonicalHashing.sha256("binding"));

        assertEquals(2, report.getReadableRootCount());
        assertEquals(2, report.getWritableRootCount());
        assertTrue(report.isMultiRepository());
        assertFalse(report.toString().contains("/workspace"));

        RuntimePreflightReport single = new RuntimePreflightReport(
                AgentType.CODEX, "0.145.0", "CODEX_WORKBENCH@1",
                SandboxMode.READ_ONLY, 1, 0,
                CanonicalHashing.sha256("binding"));
        assertFalse(single.isMultiRepository());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimePreflightReport(
                        AgentType.CODEX, "0.145.0", "CODEX_WORKBENCH@1",
                        SandboxMode.READ_ONLY, 0, 0,
                        CanonicalHashing.sha256("binding")));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimePreflightReport(
                        AgentType.CODEX, "0.145.0", "CODEX_WORKBENCH@1",
                        SandboxMode.READ_ONLY, 1, 2,
                        CanonicalHashing.sha256("binding")));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimePreflightReport(
                        AgentType.CODEX, "0.145.0", "CODEX_WORKBENCH@1",
                        SandboxMode.READ_ONLY, 1, 1,
                        CanonicalHashing.sha256("binding")));
    }

    @Test
    void exceptionShouldCarryStableCodeAndRequireSafeMessage() {
        RuntimePreflightException failure = new RuntimePreflightException(
                RuntimePreflightErrorCode.RUNTIME_VERSION_NOT_ALLOWED,
                "Runtime version is not allowed");

        assertEquals(RuntimePreflightErrorCode.RUNTIME_VERSION_NOT_ALLOWED,
                failure.getErrorCode());
        assertEquals("Runtime version is not allowed", failure.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimePreflightException(
                        RuntimePreflightErrorCode.RUNTIME_PROBE_FAILED, " "));
    }

    private ResolvedCapabilityBinding binding() {
        return ResolvedCapabilityBinding.resolve(
                "policy@1", "profile", "1.0.0",
                CanonicalHashing.sha256("profile"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                "CODEX_WORKBENCH@1");
    }
}
