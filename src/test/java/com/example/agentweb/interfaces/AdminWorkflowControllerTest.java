package com.example.agentweb.interfaces;

import com.example.agentweb.app.workflow.WorkflowAppService;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workflow.Workflow;
import com.example.agentweb.domain.workflow.WorkflowExecution;
import com.example.agentweb.domain.workflow.WorkflowStatus;
import com.example.agentweb.domain.workflow.WorkflowStep;
import com.example.agentweb.domain.workflow.WorkflowStepExecution;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.auth.ApiKeyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link AdminWorkflowController}.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@WebMvcTest(AdminWorkflowController.class)
@Import(GlobalExceptionHandler.class)
class AdminWorkflowControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private WorkflowAppService workflowAppService;
    @MockBean
    private ApiKeyProperties apiKeyProperties;
    @MockBean
    private AuthProperties authProperties;

    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;

    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @Test
    void create_should_return_workflow_definition() throws Exception {
        // Given
        when(workflowAppService.create(any())).thenReturn(buildWorkflow());

        // When & Then
        mvc.perform(post("/api/admin-workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Review\",\"agentType\":\"CODEX\",\"workingDir\":\"E:/repo\","
                                + "\"steps\":[{\"name\":\"review\",\"promptTemplate\":\"Review\",\"timeoutSeconds\":1800}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-1"))
                .andExpect(jsonPath("$.agentType").value("CODEX"))
                .andExpect(jsonPath("$.steps[0].name").value("review"));
    }

    @Test
    void run_should_return_running_execution_id() throws Exception {
        // Given
        WorkflowExecution execution = new WorkflowExecution("exec-1", "wf-1", WorkflowStatus.RUNNING,
                "{\"branch\":\"main\"}", Instant.parse("2026-06-12T08:00:00Z"), null, null, "u1");
        when(workflowAppService.run(eq("wf-1"), any())).thenReturn(execution);

        // When & Then
        mvc.perform(post("/api/admin-workflows/wf-1/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputs\":{\"branch\":\"main\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value("exec-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getExecution_should_return_execution_detail_with_steps() throws Exception {
        // Given
        WorkflowExecution execution = new WorkflowExecution("exec-1", "wf-1", WorkflowStatus.SUCCEEDED,
                "{}", Instant.parse("2026-06-12T08:00:00Z"),
                Instant.parse("2026-06-12T08:01:00Z"), null, "u1");
        WorkflowStepExecution step = new WorkflowStepExecution(
                "step-1", "exec-1", 0, "review", WorkflowStatus.SUCCEEDED,
                "prompt", "output", null, Instant.parse("2026-06-12T08:00:00Z"),
                Instant.parse("2026-06-12T08:01:00Z"));
        when(workflowAppService.getExecution("exec-1")).thenReturn(execution);
        when(workflowAppService.getWorkflow("wf-1")).thenReturn(buildWorkflow());
        when(workflowAppService.listStepExecutions("exec-1")).thenReturn(Collections.singletonList(step));

        // When & Then
        mvc.perform(get("/api/admin-workflow-executions/exec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("exec-1"))
                .andExpect(jsonPath("$.workflow.id").value("wf-1"))
                .andExpect(jsonPath("$.steps[0].output").value("output"));
    }

    @Test
    void getExecution_should_keep_history_visible_When_WorkflowDefinitionDeleted() throws Exception {
        // Given
        WorkflowExecution execution = new WorkflowExecution("exec-1", "wf-deleted", WorkflowStatus.SUCCEEDED,
                "{}", Instant.parse("2026-06-12T08:00:00Z"),
                Instant.parse("2026-06-12T08:01:00Z"), null, "u1");
        when(workflowAppService.getExecution("exec-1")).thenReturn(execution);
        when(workflowAppService.getWorkflow("wf-deleted"))
                .thenThrow(new IllegalArgumentException("工作流不存在: wf-deleted"));
        when(workflowAppService.listStepExecutions("exec-1")).thenReturn(Collections.emptyList());

        // When & Then
        mvc.perform(get("/api/admin-workflow-executions/exec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("exec-1"))
                .andExpect(jsonPath("$.workflow").doesNotExist())
                .andExpect(jsonPath("$.steps.length()").value(0));
    }

    @Test
    void getWorkflow_missing_should_return_bad_request() throws Exception {
        // Given
        when(workflowAppService.getWorkflow("missing")).thenThrow(new IllegalArgumentException("workflow 不存在"));

        // When & Then
        mvc.perform(get("/api/admin-workflows/missing"))
                .andExpect(status().isBadRequest());
    }

    private Workflow buildWorkflow() {
        return new Workflow("wf-1", "Review", "desc", AgentType.CODEX, "E:/repo",
                Collections.singletonList(new WorkflowStep("review", "Review", 1800L)),
                true, "u1", Instant.parse("2026-06-12T08:00:00Z"),
                Instant.parse("2026-06-12T08:00:00Z"));
    }
}
