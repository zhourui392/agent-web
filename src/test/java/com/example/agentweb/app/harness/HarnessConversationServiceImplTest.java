package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.CapabilityRequest;
import com.example.agentweb.domain.harness.HarnessCommandId;
import com.example.agentweb.domain.harness.HarnessStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段对话应用服务只验证准备、能力快照和 Runtime 启动编排。
 *
 * @author alex
 * @since 2026-07-24
 */
@ExtendWith(MockitoExtension.class)
class HarnessConversationServiceImplTest {

    @Mock
    private HarnessConversationPreparer preparer;
    @Mock
    private HarnessCapabilityService capabilityService;
    @Mock
    private HarnessExecutionService executionService;

    private HarnessConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new HarnessConversationServiceImpl(
                preparer, capabilityService, executionService);
    }

    @Test
    void send_should_prepare_snapshot_then_launch_runtime_with_safe_defaults() {
        StartHarnessConversationCommand command = new StartHarnessConversationCommand(
                "run-1", HarnessStage.IMPLEMENTATION, "message-1", "补充并发测试");
        when(preparer.prepare(command)).thenReturn(
                new PreparedHarnessConversation("run-1", HarnessStage.IMPLEMENTATION,
                        2, false));
        String executionKey = HarnessCommandId.conversationExecution(
                "run-1", HarnessStage.IMPLEMENTATION, "message-1");
        when(executionService.start(any(StartHarnessExecutionCommand.class)))
                .thenReturn(new HarnessExecutionResult("exec-2", "run-1",
                        HarnessStage.IMPLEMENTATION, "STARTING", false, 2));

        HarnessConversationTurnResult result = service.send(command);

        assertEquals("exec-2", result.getExecutionId());
        assertEquals(2, result.getAttemptNumber());
        assertEquals("STARTING", result.getExecutionStatus());
        ArgumentCaptor<ResolveHarnessCapabilityCommand> capability =
                ArgumentCaptor.forClass(ResolveHarnessCapabilityCommand.class);
        verify(capabilityService).resolve(capability.capture());
        assertEquals("补充并发测试", capability.getValue().getCurrentInput());
        assertTrue(capability.getValue().getCapabilityGrant()
                .permits(CapabilityRequest.fileWrite("workspace")));
        assertTrue(capability.getValue().getCapabilityGrant()
                .permits(CapabilityRequest.command("mvn-test")));
        InOrder order = inOrder(preparer, capabilityService, executionService);
        order.verify(preparer).prepare(command);
        order.verify(capabilityService).resolve(capability.getValue());
        ArgumentCaptor<StartHarnessExecutionCommand> execution =
                ArgumentCaptor.forClass(StartHarnessExecutionCommand.class);
        order.verify(executionService).start(execution.capture());
        assertEquals(executionKey, execution.getValue().getIdempotencyKey());
    }

    @Test
    void send_should_expose_idempotent_preparation_or_execution() {
        StartHarnessConversationCommand command = new StartHarnessConversationCommand(
                "run-1", HarnessStage.ANALYSIS, "message-1", "重新整理需求");
        when(preparer.prepare(command)).thenReturn(
                new PreparedHarnessConversation("run-1", HarnessStage.ANALYSIS, 1, true));
        String executionKey = HarnessCommandId.conversationExecution(
                "run-1", HarnessStage.ANALYSIS, "message-1");
        when(executionService.start(any(StartHarnessExecutionCommand.class)))
                .thenReturn(new HarnessExecutionResult("exec-1", "run-1",
                        HarnessStage.ANALYSIS, "SUCCEEDED", true, 1));

        HarnessConversationTurnResult result = service.send(command);

        assertTrue(result.isDuplicated());
        assertEquals("SUCCEEDED", result.getExecutionStatus());
    }
}
