package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.HarnessExecutionResult;
import com.example.agentweb.app.harness.HarnessExecutionService;
import com.example.agentweb.app.harness.RuntimeExecutionQueryService;
import com.example.agentweb.app.harness.RuntimeExecutionView;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.RuntimeExecutionIdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RuntimeExecution API 的参数、幂等、查询脱敏与错误映射测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(HarnessExecutionController.class)
@TestPropertySource(properties = "agent.harness.enabled=true")
@Import(GlobalExceptionHandler.class)
class HarnessExecutionControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private HarnessExecutionService executionService;

    @MockBean
    private RuntimeExecutionQueryService queryService;

    @Test
    void startAndGetShouldExposeExecutionWithoutRuntimeHandleOrSecretReferences() throws Exception {
        when(executionService.start(org.mockito.ArgumentMatchers.any())).thenReturn(
                new HarnessExecutionResult("exec-1", "run-1", HarnessStage.ANALYSIS,
                        "RUNNING", false, 1));
        RuntimeExecutionView view = mock(RuntimeExecutionView.class);
        when(view.getExecutionId()).thenReturn("exec-1");
        when(view.getStatus()).thenReturn("RUNNING");
        when(view.getSelectedMcpServers()).thenReturn(Collections.emptyList());
        when(queryService.find("run-1",
                com.example.agentweb.domain.harness.HarnessStage.ANALYSIS, 1))
                .thenReturn(Optional.of(view));

        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/executions")
                        .header("Idempotency-Key", "launch-1"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location",
                        "/api/harness/runs/run-1/stages/ANALYSIS/attempts/1/execution"))
                .andExpect(jsonPath("$.executionId").value("exec-1"));

        mvc.perform(get("/api/harness/runs/run-1/stages/ANALYSIS/attempts/1/execution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value("exec-1"))
                .andExpect(jsonPath("$.runtimeHandle").doesNotExist())
                .andExpect(jsonPath("$.secretReferences").doesNotExist());
    }

    @Test
    void duplicatedResourceShouldUseCanonicalStageAttemptAndPersistedStatus() throws Exception {
        when(executionService.start(org.mockito.ArgumentMatchers.any())).thenReturn(
                new HarnessExecutionResult("exec-existing", "run-1", HarnessStage.ANALYSIS,
                        "SUCCEEDED", true, 2));

        mvc.perform(post("/api/harness/runs/run-1/stages/DESIGN/executions")
                        .header("Idempotency-Key", "launch-1"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location",
                        "/api/harness/runs/run-1/stages/ANALYSIS/attempts/2/execution"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.duplicated").value(true));
    }

    @Test
    void sameIdempotencyKeyForDifferentStageShouldMapToConflict() throws Exception {
        when(executionService.start(org.mockito.ArgumentMatchers.any())).thenThrow(
                new RuntimeExecutionIdempotencyConflictException(
                        "run-1", HarnessStage.ANALYSIS, HarnessStage.DESIGN));

        mvc.perform(post("/api/harness/runs/run-1/stages/DESIGN/executions")
                        .header("Idempotency-Key", "launch-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "HARNESS_EXECUTION_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void invalidIdempotencyAndMissingExecutionShouldMapTo400And404() throws Exception {
        when(queryService.find("missing",
                com.example.agentweb.domain.harness.HarnessStage.ANALYSIS, 1))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/executions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HARNESS_IDEMPOTENCY_KEY"));
        mvc.perform(get("/api/harness/runs/missing/stages/ANALYSIS/attempts/1/execution"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HARNESS_RUNTIME_EXECUTION_NOT_FOUND"));
    }
}
