package com.example.agentweb.app.workflow;

import com.example.agentweb.app.agentrun.AgentRunContext;
import com.example.agentweb.app.agentrun.PromptAssemblyResult;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.PromptPart;
import com.example.agentweb.app.agentrun.RunRecallPolicyFactory;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workflow.Workflow;
import com.example.agentweb.domain.workflow.WorkflowExecution;
import com.example.agentweb.domain.workflow.WorkflowStatus;
import com.example.agentweb.domain.workflow.WorkflowStep;
import com.example.agentweb.domain.workflow.WorkflowStepExecution;
import com.example.agentweb.domain.workflow.WorkflowExecutionRepository;
import com.example.agentweb.app.agentrun.port.AgentCliInvoker;
import com.example.agentweb.app.agentrun.port.CliInvokeException;
import com.example.agentweb.infra.AgentRunProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工作流串行执行测试")
class WorkflowRunnerTest {

    @Mock
    private AgentCliInvoker cliInvoker;
    @Mock
    private WorkflowExecutionRepository executionRepository;
    @Spy
    private PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    @Mock
    private PromptAssemblyService promptAssemblyService;
    private final RunRecallPolicyFactory runRecallPolicyFactory =
            new RunRecallPolicyFactory(new AgentRunProperties());
    private WorkflowRunner runner;

    @Test
    @DisplayName("正常场景 - 多步骤按顺序执行且后一步可引用前一步输出")
    void should_RunStepsInOrderAndUsePreviousOutput_When_AllStepsSucceed() {
        // Given
        stubPromptAssemblyPassThrough();
        runner = new WorkflowRunner(cliInvoker, executionRepository, renderer,
                promptAssemblyService, runRecallPolicyFactory);
        Workflow workflow = buildWorkflow(
                new WorkflowStep("review", "Review {{inputs.branch}}", 30L),
                new WorkflowStep("summary", "Summarize {{steps.review.output}}", 30L));
        WorkflowExecution execution = buildExecution("exec-1");
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("branch", "main");
        when(cliInvoker.invokeSync(AgentType.CODEX, "E:/repo", "Review main", 30L))
                .thenReturn("review-output");
        when(cliInvoker.invokeSync(AgentType.CODEX, "E:/repo", "Summarize review-output", 30L))
                .thenReturn("summary-output");

        // When
        runner.run(workflow, execution, inputs);

        // Then
        assertEquals(WorkflowStatus.SUCCEEDED, execution.getStatus());
        InOrder order = inOrder(cliInvoker, executionRepository);
        order.verify(executionRepository).saveStep(any(WorkflowStepExecution.class));
        order.verify(cliInvoker).invokeSync(AgentType.CODEX, "E:/repo", "Review main", 30L);
        order.verify(executionRepository).updateStep(any(WorkflowStepExecution.class));
        order.verify(executionRepository).saveStep(any(WorkflowStepExecution.class));
        order.verify(cliInvoker).invokeSync(AgentType.CODEX, "E:/repo", "Summarize review-output", 30L);
        order.verify(executionRepository).updateStep(any(WorkflowStepExecution.class));
        verify(executionRepository).update(execution);
    }

    @Test
    @DisplayName("异常场景 - 任一步失败后中断后续步骤并标记执行失败")
    void should_StopRemainingStepsAndFailExecution_When_StepFails() {
        // Given
        stubPromptAssemblyPassThrough();
        runner = new WorkflowRunner(cliInvoker, executionRepository, renderer,
                promptAssemblyService, runRecallPolicyFactory);
        Workflow workflow = buildWorkflow(
                new WorkflowStep("prepare", "Prepare", 30L),
                new WorkflowStep("review", "Review {{steps.prepare.output}}", 30L),
                new WorkflowStep("summary", "Summary", 30L));
        WorkflowExecution execution = buildExecution("exec-2");
        when(cliInvoker.invokeSync(AgentType.CODEX, "E:/repo", "Prepare", 30L))
                .thenReturn("prepare-output");
        when(cliInvoker.invokeSync(AgentType.CODEX, "E:/repo", "Review prepare-output", 30L))
                .thenThrow(new CliInvokeException(CliInvokeException.Reason.NON_ZERO_EXIT, "failed"));

        // When
        runner.run(workflow, execution, Collections.emptyMap());

        // Then
        assertEquals(WorkflowStatus.FAILED, execution.getStatus());
        verify(cliInvoker, never()).invokeSync(AgentType.CODEX, "E:/repo", "Summary", 30L);
        verify(executionRepository).update(execution);
    }

    @Test
    @DisplayName("正常场景 - 执行步骤时使用 AgentRun 组装后的 prompt")
    void should_InvokeCliWithAssembledPrompt_When_PromptAssemblyAddsContext() {
        // Given
        when(promptAssemblyService.assemble(any(AgentRunContext.class))).thenAnswer(inv -> {
            AgentRunContext context = inv.getArgument(0);
            return promptResult("[Workspace Context]\n" + context.getOriginalInput());
        });
        runner = new WorkflowRunner(cliInvoker, executionRepository, renderer,
                promptAssemblyService, runRecallPolicyFactory);
        Workflow workflow = buildWorkflow(new WorkflowStep("review", "Review {{inputs.branch}}", 30L));
        WorkflowExecution execution = buildExecution("exec-3");
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("branch", "feature/demo");
        when(cliInvoker.invokeSync(AgentType.CODEX, "E:/repo",
                "[Workspace Context]\nReview feature/demo", 30L)).thenReturn("ok");

        // When
        runner.run(workflow, execution, inputs);

        // Then
        verify(cliInvoker).invokeSync(AgentType.CODEX, "E:/repo",
                "[Workspace Context]\nReview feature/demo", 30L);
        verify(executionRepository).saveStep(org.mockito.ArgumentMatchers.argThat(
                step -> "[Workspace Context]\nReview feature/demo".equals(step.getPrompt())));
        assertEquals(WorkflowStatus.SUCCEEDED, execution.getStatus());
    }

    private Workflow buildWorkflow(WorkflowStep... steps) {
        return new Workflow(
                "wf-1",
                "Review Current Branch",
                "Run review",
                AgentType.CODEX,
                "E:/repo",
                Arrays.asList(steps),
                true,
                "u1",
                Instant.parse("2026-06-12T08:00:00Z"),
                Instant.parse("2026-06-12T08:00:00Z"));
    }

    private WorkflowExecution buildExecution(String id) {
        return new WorkflowExecution(
                id,
                "wf-1",
                WorkflowStatus.RUNNING,
                "{}",
                Instant.parse("2026-06-12T08:00:00Z"),
                null,
                null,
                "u1");
    }

    private void stubPromptAssemblyPassThrough() {
        when(promptAssemblyService.assemble(any(AgentRunContext.class))).thenAnswer(inv -> {
            AgentRunContext context = inv.getArgument(0);
            return promptResult(context.getOriginalInput());
        });
    }

    private PromptAssemblyResult promptResult(String prompt) {
        return new PromptAssemblyResult(
                prompt,
                "hash",
                Collections.<PromptPart>emptyList(),
                null,
                null,
                Collections.emptyList(),
                "none");
    }
}
