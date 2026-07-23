package com.example.agentweb.app.workflow;

import com.example.agentweb.app.agentrun.AgentRunContext;
import com.example.agentweb.app.agentrun.PromptAssemblyResult;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.RunForm;
import com.example.agentweb.app.agentrun.RunRecallPolicyFactory;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.workflow.Workflow;
import com.example.agentweb.domain.workflow.WorkflowExecution;
import com.example.agentweb.domain.workflow.WorkflowExecutionRepository;
import com.example.agentweb.domain.workflow.WorkflowStatus;
import com.example.agentweb.domain.workflow.WorkflowStep;
import com.example.agentweb.domain.workflow.WorkflowStepExecution;
import com.example.agentweb.app.agentrun.port.AgentCliInvoker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流串行执行器。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Component
@Slf4j
public class WorkflowRunner {

    private final AgentCliInvoker cliInvoker;
    private final WorkflowExecutionRepository executionRepository;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final PromptAssemblyService promptAssemblyService;
    private final RunRecallPolicyFactory runRecallPolicyFactory;

    public WorkflowRunner(AgentCliInvoker cliInvoker, WorkflowExecutionRepository executionRepository,
                          PromptTemplateRenderer promptTemplateRenderer,
                          PromptAssemblyService promptAssemblyService,
                          RunRecallPolicyFactory runRecallPolicyFactory) {
        this.cliInvoker = cliInvoker;
        this.executionRepository = executionRepository;
        this.promptTemplateRenderer = promptTemplateRenderer;
        this.promptAssemblyService = promptAssemblyService;
        this.runRecallPolicyFactory = runRecallPolicyFactory;
    }

    /**
     * 串行执行工作流步骤。
     *
     * @param workflow 工作流定义
     * @param execution 执行记录
     * @param inputs 运行输入
     */
    public void run(Workflow workflow, WorkflowExecution execution, Map<String, Object> inputs) {
        Map<String, String> stepOutputs = new HashMap<>();
        try {
            runSteps(workflow, execution, inputs, stepOutputs);
            execution.markSucceeded();
        } catch (RuntimeException e) {
            execution.markFailed(e.getMessage());
            log.warn("workflow-run-failed executionId={} workflowId={} err={}",
                    execution.getId(), workflow.getId(), e.getMessage());
        } finally {
            executionRepository.update(execution);
        }
    }

    private void runSteps(Workflow workflow, WorkflowExecution execution, Map<String, Object> inputs,
                          Map<String, String> stepOutputs) {
        int index = 0;
        for (WorkflowStep step : workflow.getSteps()) {
            runStep(workflow, execution, inputs, stepOutputs, step, index);
            index++;
        }
    }

    private void runStep(Workflow workflow, WorkflowExecution execution, Map<String, Object> inputs,
                         Map<String, String> stepOutputs, WorkflowStep step, int index) {
        PromptTemplateRenderer.RenderResult rendered =
                promptTemplateRenderer.render(step.getPromptTemplate(), inputs, stepOutputs);
        for (String warning : rendered.getWarnings()) {
            log.warn("workflow-template-warning executionId={} stepName={} warning={}",
                    execution.getId(), step.getName(), warning);
        }
        PromptAssemblyResult prompt = assemblePrompt(workflow, rendered.getText());
        WorkflowStepExecution stepExecution = new WorkflowStepExecution(
                UUID.randomUUID().toString(),
                execution.getId(),
                index,
                step.getName(),
                WorkflowStatus.RUNNING,
                prompt.getPrompt(),
                null,
                null,
                Instant.now(),
                null);
        executionRepository.saveStep(stepExecution);
        try {
            String output = cliInvoker.invokeSync(
                    workflow.getAgentType(),
                    workflow.getWorkingDir(),
                    prompt.getPrompt(),
                    step.getTimeoutSeconds());
            stepExecution.markSucceeded(output);
            stepOutputs.put(step.getName(), output);
        } catch (RuntimeException e) {
            stepExecution.markFailed(e.getMessage());
            throw e;
        } finally {
            executionRepository.updateStep(stepExecution);
        }
    }

    private PromptAssemblyResult assemblePrompt(Workflow workflow, String originalPrompt) {
        AgentRunContext context = AgentRunContext.builder()
                .originalInput(originalPrompt)
                .runForm(RunForm.WORKFLOW_STEP)
                .sourceDomain(SourceType.GENERAL)
                .agentType(workflow.getAgentType())
                .workingDir(workflow.getWorkingDir())
                .recallPolicy(runRecallPolicyFactory.forRun(RunForm.WORKFLOW_STEP, SourceType.GENERAL))
                .build();
        try {
            return promptAssemblyService.assemble(context);
        } catch (RuntimeException e) {
            log.warn("workflow-prompt-assembly-failed workflowId={} workingDir={} reason={}",
                    workflow.getId(), workflow.getWorkingDir(), e.getMessage(), e);
            return new PromptAssemblyResult(originalPrompt, "assembly-fallback",
                    java.util.Collections.emptyList(), null, null,
                    java.util.Collections.emptyList(), "none");
        }
    }
}
