package com.example.agentweb.app.harness;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilitySnapshotRepository;
import com.example.agentweb.domain.harness.ExecutionPermit;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionIdempotencyConflictException;
import com.example.agentweb.domain.harness.RuntimeExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RuntimeExecution 事务准备的幂等请求语义测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(MockitoExtension.class)
class HarnessExecutionPreparerTest {

    private static final Instant NOW = Instant.parse("2026-07-23T18:00:00Z");

    @Mock
    private HarnessRunRepository runRepository;
    @Mock
    private CapabilitySnapshotRepository snapshotRepository;
    @Mock
    private RuntimeExecutionRepository executionRepository;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private HarnessIdGenerator idGenerator;

    @Test
    void sameKeyForDifferentStageShouldConflictBeforeLoadingRunOrSnapshot() {
        RuntimeExecution existing = RuntimeExecution.prepare("exec-1", "launch-1",
                new ExecutionPermit("run-1", HarnessStage.ANALYSIS, 1,
                        hash('a'), hash('b'), Collections.emptySet()),
                AgentRuntime.CODEX, NOW);
        when(executionRepository.findByIdempotencyKey("run-1", "launch-1"))
                .thenReturn(Optional.of(existing));
        HarnessExecutionPreparer preparer = new HarnessExecutionPreparer(runRepository,
                snapshotRepository, executionRepository, currentUserProvider, idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(RuntimeExecutionIdempotencyConflictException.class,
                () -> preparer.prepare(new StartHarnessExecutionCommand(
                        "run-1", HarnessStage.DESIGN, "launch-1")));

        verify(runRepository, never()).findById("run-1");
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
