package com.example.agentweb.interfaces;

import com.example.agentweb.app.workflow.WorkflowAppService;
import com.example.agentweb.app.workflow.WorkflowCreateCommand;
import com.example.agentweb.app.workflow.WorkflowRunCommand;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workflow.Workflow;
import com.example.agentweb.domain.workflow.WorkflowExecution;
import com.example.agentweb.domain.workflow.WorkflowStep;
import com.example.agentweb.interfaces.dto.SuccessResponse;
import com.example.agentweb.interfaces.dto.WorkflowDto;
import com.example.agentweb.interfaces.dto.WorkflowExecutionDetailResponse;
import com.example.agentweb.interfaces.dto.WorkflowExecutionDto;
import com.example.agentweb.interfaces.dto.WorkflowRunRequest;
import com.example.agentweb.interfaces.dto.WorkflowRunResponse;
import com.example.agentweb.interfaces.dto.WorkflowSaveRequest;
import com.example.agentweb.interfaces.dto.WorkflowStepRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理后台工作流 API。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@RestController
public class AdminWorkflowController {

    private final WorkflowAppService workflowAppService;

    public AdminWorkflowController(WorkflowAppService workflowAppService) {
        this.workflowAppService = workflowAppService;
    }

    /**
     * 查询工作流定义列表。
     *
     * @return 工作流列表
     */
    @GetMapping(path = "/api/admin-workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<WorkflowDto> listWorkflows() {
        return workflowAppService.listWorkflows().stream()
                .map(WorkflowDto::from)
                .collect(Collectors.toList());
    }

    /**
     * 创建工作流定义。
     *
     * @param request 保存请求
     * @return 工作流定义
     */
    @PostMapping(path = "/api/admin-workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkflowDto create(@Valid @RequestBody WorkflowSaveRequest request) {
        return WorkflowDto.from(workflowAppService.create(toCommand(request)));
    }

    /**
     * 查询工作流定义。
     *
     * @param id 工作流 ID
     * @return 工作流定义
     */
    @GetMapping(path = "/api/admin-workflows/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkflowDto get(@PathVariable String id) {
        return WorkflowDto.from(workflowAppService.getWorkflow(id));
    }

    /**
     * 更新工作流定义。
     *
     * @param id 工作流 ID
     * @param request 保存请求
     * @return 工作流定义
     */
    @PutMapping(path = "/api/admin-workflows/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkflowDto update(@PathVariable String id, @Valid @RequestBody WorkflowSaveRequest request) {
        return WorkflowDto.from(workflowAppService.update(id, toCommand(request)));
    }

    /**
     * 删除工作流定义,执行历史保留。
     *
     * @param id 工作流 ID
     * @return 成功标记
     */
    @DeleteMapping(path = "/api/admin-workflows/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SuccessResponse delete(@PathVariable String id) {
        workflowAppService.delete(id);
        return new SuccessResponse(true);
    }

    /**
     * 触发工作流运行。
     *
     * @param id 工作流 ID
     * @param request 运行请求
     * @return 执行 ID
     */
    @PostMapping(path = "/api/admin-workflows/{id}/run", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkflowRunResponse run(@PathVariable String id, @RequestBody(required = false) WorkflowRunRequest request) {
        WorkflowRunCommand command = new WorkflowRunCommand(request == null ? null : request.getInputs());
        WorkflowExecution execution = workflowAppService.run(id, command);
        return new WorkflowRunResponse(execution);
    }

    /**
     * 查询执行记录列表。
     *
     * @param workflowId 工作流 ID,可空
     * @param page 页码
     * @param size 每页大小
     * @return 执行记录列表
     */
    @GetMapping(path = "/api/admin-workflow-executions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<WorkflowExecutionDto>> listExecutions(
            @RequestParam(value = "workflowId", required = false) String workflowId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        List<WorkflowExecutionDto> body = workflowAppService.listExecutions(workflowId, page, size).stream()
                .map(WorkflowExecutionDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(workflowAppService.countExecutions(workflowId)))
                .body(body);
    }

    /**
     * 查询执行详情。
     *
     * @param id 执行 ID
     * @return 执行详情
     */
    @GetMapping(path = "/api/admin-workflow-executions/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkflowExecutionDetailResponse getExecution(@PathVariable String id) {
        WorkflowExecution execution = workflowAppService.getExecution(id);
        Workflow workflow = findWorkflowOrNull(execution.getWorkflowId());
        return new WorkflowExecutionDetailResponse(
                execution, workflow, workflowAppService.listStepExecutions(id));
    }

    private Workflow findWorkflowOrNull(String workflowId) {
        try {
            return workflowAppService.getWorkflow(workflowId);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private WorkflowCreateCommand toCommand(WorkflowSaveRequest request) {
        return new WorkflowCreateCommand(
                request.getName(),
                request.getDescription(),
                parseAgentType(request.getAgentType()),
                request.getWorkingDir(),
                toSteps(request.getSteps()),
                request.getEnabled() == null || request.getEnabled());
    }

    private AgentType parseAgentType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return AgentType.CODEX;
        }
        return AgentType.valueOf(raw.trim().toUpperCase());
    }

    private List<WorkflowStep> toSteps(List<WorkflowStepRequest> requests) {
        List<WorkflowStep> steps = new ArrayList<>();
        for (WorkflowStepRequest request : requests) {
            steps.add(new WorkflowStep(
                    request.getName(),
                    request.getPromptTemplate(),
                    request.getTimeoutSeconds()));
        }
        return steps;
    }
}
