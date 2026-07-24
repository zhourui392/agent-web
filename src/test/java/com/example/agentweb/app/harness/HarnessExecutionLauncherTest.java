package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.AgentExecutionSpec;
import com.example.agentweb.app.harness.port.AgentRuntimeGateway;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CancellationDirective;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.RuntimeEnforcementProfile;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.WorkspaceRuntimeInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 非事务 Launcher 对 Prepare/Activate/Gateway 与取消副作用的编排测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(MockitoExtension.class)
class HarnessExecutionLauncherTest {

    @Mock
    private HarnessExecutionPreparer preparer;
    @Mock
    private HarnessRuntimeEventService runtimeEventService;
    @Mock
    private AgentRuntimeGateway runtimeGateway;

    private HarnessExecutionLauncher launcher;

    @BeforeEach
    void setUp() {
        launcher = new HarnessExecutionLauncher(preparer, runtimeEventService, runtimeGateway);
    }

    @Test
    void shouldLaunchOnlyAfterPrepareAndActivateTransactionsReturn() {
        StartHarnessExecutionCommand command = new StartHarnessExecutionCommand(
                "run-1", HarnessStage.ANALYSIS, "launch-1");
        AgentExecutionSpec spec = spec();
        PreparedHarnessExecution prepared = new PreparedHarnessExecution(spec);
        HarnessExecutionResult persisted = result("RUNNING", false);
        when(preparer.prepare(command)).thenReturn(prepared);
        when(preparer.activate("exec-1")).thenReturn(true);
        when(preparer.result("exec-1", false)).thenReturn(persisted);

        HarnessExecutionResult result = launcher.start(command);

        assertEquals("exec-1", result.getExecutionId());
        assertEquals("RUNNING", result.getStatus());
        InOrder order = inOrder(preparer, runtimeGateway);
        order.verify(preparer).prepare(command);
        order.verify(preparer).activate("exec-1");
        order.verify(runtimeGateway).start(spec, runtimeEventService);
        order.verify(preparer).result("exec-1", false);
    }

    @Test
    void idempotentActiveExecutionShouldNotStartSecondProcess() {
        StartHarnessExecutionCommand command = new StartHarnessExecutionCommand(
                "run-1", HarnessStage.ANALYSIS, "launch-1");
        when(preparer.prepare(command)).thenReturn(new PreparedHarnessExecution(spec(), true));
        when(preparer.activate("exec-1")).thenReturn(false);
        when(preparer.result("exec-1", true)).thenReturn(result("SUCCEEDED", true));

        HarnessExecutionResult result = launcher.start(command);

        assertEquals("SUCCEEDED", result.getStatus());
        assertTrue(result.isDuplicated());
        verify(runtimeGateway, never()).start(spec(), runtimeEventService);
    }

    @Test
    void synchronousStartFailureShouldReturnPersistedFailedState() {
        StartHarnessExecutionCommand command = new StartHarnessExecutionCommand(
                "run-1", HarnessStage.ANALYSIS, "launch-1");
        AgentExecutionSpec spec = spec();
        when(preparer.prepare(command)).thenReturn(new PreparedHarnessExecution(spec));
        when(preparer.activate("exec-1")).thenReturn(true);
        doThrow(new com.example.agentweb.app.harness.port.AgentRuntimeStartException(
                "spawn failed", true, new IllegalStateException("missing")))
                .when(runtimeGateway).start(spec, runtimeEventService);
        when(preparer.result("exec-1", false)).thenReturn(result("FAILED", false));

        HarnessExecutionResult result = launcher.start(command);

        assertEquals("FAILED", result.getStatus());
        verify(runtimeEventService).recordStartFailure("exec-1",
                "AgentRuntimeStartException", true);
    }

    @Test
    void shouldTerminateRuntimeOnlyAfterCancellationIntentPreparationReturns() {
        PreparedHarnessCancellation prepared = new PreparedHarnessCancellation(
                HarnessMutationResult.of("run-1", "CANCELLING", 3L, false),
                CancellationDirective.cancelRuntime("exec-1"));
        when(preparer.prepareCancellation("run-1", "stop")).thenReturn(prepared);

        launcher.cancel("run-1", "stop");

        InOrder order = inOrder(preparer, runtimeGateway);
        order.verify(preparer).prepareCancellation("run-1", "stop");
        order.verify(runtimeGateway).cancel("exec-1");
    }

    private AgentExecutionSpec spec() {
        return new AgentExecutionSpec("exec-1", "run-1", HarnessStage.ANALYSIS, 1,
                AgentRuntime.CODEX, "/workspace", "prompt", hash('a'), hash('b'),
                Collections.emptyList(), new RuntimeEnforcementProfile(
                "codex@1", "codex-test", "read-only", true, true, true),
                WorkspaceRuntimeInventory.empty(),
                StageContract.mvpDefaults().get(0).getRequiredOutputArtifacts());
    }

    private HarnessExecutionResult result(String status, boolean duplicated) {
        return new HarnessExecutionResult("exec-1", "run-1", HarnessStage.ANALYSIS,
                status, duplicated, 1);
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
