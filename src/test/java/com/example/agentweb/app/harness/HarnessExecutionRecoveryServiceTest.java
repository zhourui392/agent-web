package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.DeploymentExecution;
import com.example.agentweb.domain.harness.DeploymentExecutionRepository;
import com.example.agentweb.domain.harness.DeploymentPermit;
import com.example.agentweb.domain.harness.DeploymentTemplateReference;
import com.example.agentweb.domain.harness.ExecutionPermit;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionRepository;
import com.example.agentweb.domain.harness.RuntimeExecutionStatus;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用重启后外部动作不重放、Runtime LOST 和部署人工对账测试。
 *
 * @author alex
 * @since 2026-07-23
 */
@ExtendWith(MockitoExtension.class)
class HarnessExecutionRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Mock
    private RuntimeExecutionRepository runtimeRepository;
    @Mock
    private DeploymentExecutionRepository deploymentRepository;
    @Mock
    private HarnessRunRepository runRepository;

    @Test
    void shouldCloseUnknownRuntimeAndRequireDeploymentReconciliation() {
        HarnessRun run = HarnessRun.create("run-1", "M4", "/workspace", "CODEX", "local",
                "harness@1", "admin", "create", StageContract.mvpDefaults(), NOW);
        run.startStage(HarnessStage.ANALYSIS, "start", NOW.plusSeconds(1));
        ExecutionPermit runtimePermit = run.authorizeExecution(HarnessStage.ANALYSIS,
                new com.example.agentweb.domain.harness.CapabilitySnapshotReference("run-1",
                        HarnessStage.ANALYSIS, 1, hash('a'), hash('b'), Collections.emptySet()));
        RuntimeExecution runtime = RuntimeExecution.prepare("runtime-1", "launch", runtimePermit,
                AgentRuntime.CODEX, NOW.plusSeconds(2));
        run.bindExecution(runtime.reference(), NOW.plusSeconds(2));
        DeploymentExecution deployment = DeploymentExecution.prepare("deploy-1", "deploy-key",
                new DeploymentPermit("run-1", 1, hash('c'), baseline()),
                new DeploymentTemplateReference("local", "1", hash('d'), true),
                NOW.plusSeconds(2));
        when(runtimeRepository.findUnfinished()).thenReturn(Collections.singletonList(runtime));
        when(deploymentRepository.findUnfinished()).thenReturn(Collections.singletonList(deployment));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        new HarnessExecutionRecoveryService(runtimeRepository, deploymentRepository,
                runRepository, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC))
                .recoverUnfinishedExternalActions();

        assertEquals(RuntimeExecutionStatus.LOST, runtime.getStatus());
        assertEquals(com.example.agentweb.domain.harness.DeploymentExecutionStatus.RECONCILIATION_REQUIRED,
                deployment.getStatus());
        verify(runtimeRepository).update(runtime);
        verify(deploymentRepository).update(deployment);
    }

    private WorkspaceBaseline baseline() {
        return WorkspaceBaseline.capture("/workspace", "feat/m4",
                "0123456789012345678901234567890123456789", false, hash('e'), NOW);
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
