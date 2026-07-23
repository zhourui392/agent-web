package com.example.agentweb.app.workflow;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workflow.Workflow;
import com.example.agentweb.domain.workflow.WorkflowExecution;
import com.example.agentweb.domain.workflow.WorkflowExecutionRepository;
import com.example.agentweb.domain.workflow.WorkflowRepository;
import com.example.agentweb.domain.workflow.WorkflowStatus;
import com.example.agentweb.domain.workflow.WorkflowStepExecution;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 工作流应用服务。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Service
public class WorkflowAppService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowRunner workflowRunner;
    private final CurrentUserProvider currentUserProvider;
    private final Executor executor;

    public WorkflowAppService(WorkflowRepository workflowRepository,
                              WorkflowExecutionRepository executionRepository,
                              WorkflowRunner workflowRunner,
                              CurrentUserProvider currentUserProvider,
                              @Qualifier("agentExecutor") Executor executor) {
        this.workflowRepository = workflowRepository;
        this.executionRepository = executionRepository;
        this.workflowRunner = workflowRunner;
        this.currentUserProvider = currentUserProvider;
        this.executor = executor;
    }

    /**
     * 创建工作流。
     *
     * @param command 创建命令
     * @return 已创建的工作流
     */
    public Workflow create(WorkflowCreateCommand command) {
        Instant now = Instant.now();
        Workflow workflow = new Workflow(
                UUID.randomUUID().toString(),
                command.getName(),
                command.getDescription(),
                command.getAgentType(),
                command.getWorkingDir(),
                command.getSteps(),
                command.isEnabled(),
                currentUserProvider.currentUserId(),
                now,
                now);
        workflowRepository.save(workflow);
        return workflow;
    }

    /**
     * 更新工作流。
     *
     * @param id 工作流 ID
     * @param command 更新命令
     * @return 更新后的工作流
     */
    public Workflow update(String id, WorkflowCreateCommand command) {
        Workflow existing = requireWorkflow(id);
        Workflow workflow = new Workflow(
                existing.getId(),
                command.getName(),
                command.getDescription(),
                command.getAgentType(),
                command.getWorkingDir(),
                command.getSteps(),
                command.isEnabled(),
                existing.getCreatedBy(),
                existing.getCreatedAt(),
                Instant.now());
        workflowRepository.update(workflow);
        return workflow;
    }

    /**
     * 查询全部工作流。
     *
     * @return 工作流列表
     */
    public List<Workflow> listWorkflows() {
        return workflowRepository.findAll();
    }

    /**
     * 查询工作流。
     *
     * @param id 工作流 ID
     * @return 工作流
     */
    public Workflow getWorkflow(String id) {
        return requireWorkflow(id);
    }

    /**
     * 删除工作流定义。
     *
     * @param id 工作流 ID
     */
    public void delete(String id) {
        workflowRepository.deleteById(id);
    }

    /**
     * 运行工作流。
     *
     * @param id 工作流 ID
     * @param command 运行命令
     * @return 已创建的执行记录
     */
    public WorkflowExecution run(String id, WorkflowRunCommand command) {
        Workflow workflow = requireWorkflow(id);
        workflow.requireRunnable();
        Map<String, Object> inputs = command == null ? Collections.emptyMap() : command.getInputs();
        WorkflowExecution execution = new WorkflowExecution(
                UUID.randomUUID().toString(),
                workflow.getId(),
                WorkflowStatus.RUNNING,
                serializeInputs(inputs),
                Instant.now(),
                null,
                null,
                currentUserProvider.currentUserId());
        executionRepository.save(execution);
        executor.execute(() -> workflowRunner.run(workflow, execution, inputs));
        return execution;
    }

    /**
     * 查询执行记录。
     *
     * @param id 执行 ID
     * @return 执行记录
     */
    public WorkflowExecution getExecution(String id) {
        WorkflowExecution execution = executionRepository.findById(id);
        if (execution == null) {
            throw new IllegalArgumentException("工作流执行不存在: " + id);
        }
        return execution;
    }

    /**
     * 分页查询执行记录。
     *
     * @param workflowId 工作流 ID,可空
     * @param page 页码,从 1 开始
     * @param size 每页条数
     * @return 执行记录列表
     */
    public List<WorkflowExecution> listExecutions(String workflowId, int page, int size) {
        int limit = clampSize(size);
        int offset = Math.max(0, page - 1) * limit;
        if (workflowId == null || workflowId.trim().isEmpty()) {
            return executionRepository.findAll(offset, limit);
        }
        return executionRepository.findByWorkflowId(workflowId.trim(), offset, limit);
    }

    /**
     * 统计执行记录。
     *
     * @param workflowId 工作流 ID,可空
     * @return 执行记录数
     */
    public long countExecutions(String workflowId) {
        if (workflowId == null || workflowId.trim().isEmpty()) {
            return executionRepository.countAll();
        }
        return executionRepository.countByWorkflowId(workflowId.trim());
    }

    /**
     * 查询步骤执行记录。
     *
     * @param executionId 执行 ID
     * @return 步骤记录列表
     */
    public List<WorkflowStepExecution> listStepExecutions(String executionId) {
        return executionRepository.findStepsByExecutionId(executionId);
    }

    private Workflow requireWorkflow(String id) {
        Workflow workflow = workflowRepository.findById(id);
        if (workflow == null) {
            throw new IllegalArgumentException("工作流不存在: " + id);
        }
        return workflow;
    }

    private String serializeInputs(Map<String, Object> inputs) {
        try {
            return MAPPER.writeValueAsString(inputs == null ? Collections.emptyMap() : inputs);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("运行输入不是合法 JSON 对象", e);
        }
    }

    private int clampSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }
}
