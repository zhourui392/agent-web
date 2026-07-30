package com.example.agentweb.app.workflow;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workflow.Workflow;
import com.example.agentweb.domain.workflow.WorkflowExecution;
import com.example.agentweb.domain.workflow.WorkflowExecutionRepository;
import com.example.agentweb.domain.workflow.WorkflowRepository;
import com.example.agentweb.domain.workflow.WorkflowStatus;
import com.example.agentweb.domain.workflow.WorkflowStep;
import com.example.agentweb.domain.workflow.WorkflowStepExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for Workflow application orchestration and repository routing.
 *
 * @author alex
 * @since 2026-07-30
 */
@ExtendWith(MockitoExtension.class)
class WorkflowAppServiceTest {

    @Mock
    private WorkflowRepository workflows;
    @Mock
    private WorkflowExecutionRepository executions;
    @Mock
    private WorkflowRunner runner;
    @Mock
    private CurrentUserProvider currentUser;

    private WorkflowAppService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowAppService(workflows, executions, runner, currentUser,
                Runnable::run);
    }

    @Test
    void createUpdateAndDefinitionQueriesShouldRouteThroughRepositories() {
        when(currentUser.currentUserId()).thenReturn("operator");
        WorkflowCreateCommand create = command("Initial", AgentType.CODEX, true);

        Workflow created = service.create(create);

        assertFalse(created.getId().isBlank());
        assertEquals("operator", created.getCreatedBy());
        verify(workflows).save(created);

        when(workflows.findById(created.getId())).thenReturn(created);
        Workflow updated = service.update(created.getId(),
                command("Updated", AgentType.CLAUDE, false));

        assertEquals(created.getId(), updated.getId());
        assertEquals(created.getCreatedAt(), updated.getCreatedAt());
        assertEquals("Updated", updated.getName());
        assertEquals(AgentType.CLAUDE, updated.getAgentType());
        verify(workflows).update(updated);

        when(workflows.findAll()).thenReturn(List.of(updated));
        assertEquals(List.of(updated), service.listWorkflows());
        assertSame(created, service.getWorkflow(created.getId()));
        service.delete(created.getId());
        verify(workflows).deleteById(created.getId());
    }

    @Test
    void runShouldPersistExecutionAndDispatchWithDefensiveInputs() {
        Workflow workflow = workflow(true);
        when(workflows.findById("wf-1")).thenReturn(workflow);
        when(currentUser.currentUserId()).thenReturn("operator");
        Map<String, Object> callerInputs = new HashMap<String, Object>();
        callerInputs.put("branch", "main");
        WorkflowRunCommand command = new WorkflowRunCommand(callerInputs);
        callerInputs.put("branch", "mutated-after-command");

        WorkflowExecution execution = service.run("wf-1", command);

        assertEquals(WorkflowStatus.RUNNING, execution.getStatus());
        assertEquals("{\"branch\":\"main\"}", execution.getInputsJson());
        assertEquals("operator", execution.getCreatedBy());
        verify(executions).save(execution);
        verify(runner).run(workflow, execution, Collections.<String, Object>singletonMap(
                "branch", "main"));
    }

    @Test
    void nullRunCommandShouldDispatchEmptyInputs() {
        Workflow workflow = workflow(true);
        when(workflows.findById("wf-1")).thenReturn(workflow);
        when(currentUser.currentUserId()).thenReturn("operator");

        WorkflowExecution execution = service.run("wf-1", null);

        assertEquals("{}", execution.getInputsJson());
        verify(runner).run(workflow, execution, Collections.<String, Object>emptyMap());
    }

    @Test
    void disabledMissingAndNonSerializableRunsShouldFailBeforeDispatch() {
        when(workflows.findById("missing")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.getWorkflow("missing"));
        assertThrows(IllegalArgumentException.class, () -> service.run("missing", null));

        when(workflows.findById("disabled")).thenReturn(workflow(false));
        assertThrows(IllegalStateException.class, () -> service.run("disabled", null));

        Workflow enabled = workflow(true);
        when(workflows.findById("wf-1")).thenReturn(enabled);
        Map<String, Object> recursive = new HashMap<String, Object>();
        recursive.put("self", recursive);
        assertThrows(IllegalArgumentException.class,
                () -> service.run("wf-1", new WorkflowRunCommand(recursive)));
        verify(executions, never()).save(any(WorkflowExecution.class));
        verify(runner, never()).run(any(Workflow.class), any(WorkflowExecution.class),
                any(Map.class));
    }

    @Test
    void executionQueriesShouldApplyPaginationAndOptionalWorkflowRouting() {
        WorkflowExecution execution = execution("exec-1");
        WorkflowStepExecution step = new WorkflowStepExecution(
                "step-1", "exec-1", 0, "review", WorkflowStatus.SUCCEEDED,
                "prompt", "output", null, Instant.parse("2026-07-30T00:00:00Z"),
                Instant.parse("2026-07-30T00:00:01Z"));
        when(executions.findById("exec-1")).thenReturn(execution);
        when(executions.findAll(0, 20)).thenReturn(List.of(execution));
        when(executions.findByWorkflowId("wf-1", 200, 100)).thenReturn(List.of(execution));
        when(executions.countAll()).thenReturn(4L);
        when(executions.countByWorkflowId("wf-1")).thenReturn(2L);
        when(executions.findStepsByExecutionId("exec-1")).thenReturn(List.of(step));

        assertSame(execution, service.getExecution("exec-1"));
        assertEquals(List.of(execution), service.listExecutions(null, 0, 0));
        assertEquals(List.of(execution), service.listExecutions("  wf-1  ", 3, 200));
        assertEquals(4L, service.countExecutions("  "));
        assertEquals(2L, service.countExecutions(" wf-1 "));
        assertEquals(List.of(step), service.listStepExecutions("exec-1"));
        assertThrows(IllegalArgumentException.class, () -> service.getExecution("missing"));
    }

    private WorkflowCreateCommand command(String name, AgentType type, boolean enabled) {
        return new WorkflowCreateCommand(name, "description", type, "/workspace",
                List.of(new WorkflowStep("review", "Review", 30L)), enabled);
    }

    private Workflow workflow(boolean enabled) {
        return new Workflow("wf-1", "Workflow", "description", AgentType.CODEX,
                "/workspace", List.of(new WorkflowStep("review", "Review", 30L)),
                enabled, "creator", Instant.parse("2026-07-30T00:00:00Z"),
                Instant.parse("2026-07-30T00:00:00Z"));
    }

    private WorkflowExecution execution(String id) {
        WorkflowExecution execution = new WorkflowExecution(
                id, "wf-1", WorkflowStatus.RUNNING, "{}",
                Instant.parse("2026-07-30T00:00:00Z"), null, null, "operator");
        assertNotNull(execution.getStartedAt());
        return execution;
    }
}
