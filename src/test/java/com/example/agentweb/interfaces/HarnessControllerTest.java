package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.HarnessAppService;
import com.example.agentweb.app.harness.HarnessMutationResult;
import com.example.agentweb.app.harness.HarnessRunQueryService;
import com.example.agentweb.app.harness.HarnessRunView;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.IllegalHarnessTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Harness 管理 API 参数、状态码、幂等键和错误映射测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(HarnessController.class)
@TestPropertySource(properties = "agent.harness.enabled=true")
@Import(GlobalExceptionHandler.class)
class HarnessControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private HarnessAppService appService;

    @MockBean
    private HarnessRunQueryService queryService;

    @Test
    void create_and_get_should_expose_management_contract() throws Exception {
        when(appService.create(any())).thenReturn(
                HarnessMutationResult.of("run-1", "DRAFT", 0L, false));
        when(queryService.findById("run-1")).thenReturn(Optional.of(emptyView()));

        mvc.perform(post("/api/harness/runs")
                        .header("Idempotency-Key", "create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"M1\",\"workingDir\":\"/workspace\","
                                + "\"agentType\":\"CODEX\",\"environment\":\"local\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/harness/runs/run-1"))
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mvc.perform(get("/api/harness/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"));
    }

    @Test
    void missing_or_oversized_idempotency_key_should_return_explicit_400() throws Exception {
        mvc.perform(post("/api/harness/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"M1\",\"workingDir\":\"/workspace\","
                                + "\"agentType\":\"CODEX\",\"environment\":\"local\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HARNESS_IDEMPOTENCY_KEY"));

        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/start")
                        .header("Idempotency-Key", String.join("", Collections.nCopies(129, "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HARNESS_IDEMPOTENCY_KEY"));
    }

    @Test
    void stage_artifact_gate_approval_retry_and_cancel_actions_should_delegate() throws Exception {
        HarnessMutationResult result = HarnessMutationResult.of("run-1", "ACTIVE", 1L, false);
        when(appService.startStage(any(), any(), any())).thenReturn(result);
        when(appService.registerArtifact(any())).thenReturn(result);
        when(appService.recordGate(any(), any(), any(), anyBoolean(), any(), any())).thenReturn(result);
        when(appService.requestApproval(any(), any())).thenReturn(result);
        when(appService.approve(any(), any(), any(), any())).thenReturn(result);
        when(appService.reject(any(), any(), any(), any())).thenReturn(result);
        when(appService.retryStage(any(), any(), any())).thenReturn(result);
        when(appService.cancel(any(), any())).thenReturn(result);

        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/start")
                        .header("Idempotency-Key", "start-key"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artifactType\":\"REQUIREMENT\",\"content\":\"body\","
                                + "\"contentType\":\"text/markdown\",\"classification\":\"INTERNAL\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/gates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rule\":\"artifact-schema-valid\",\"passed\":true,"
                                + "\"evidenceReferences\":[]}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/request-approval"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artifactBaselineHash\":\"" + repeat('a', 64)
                                + "\",\"reason\":\"ok\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artifactBaselineHash\":\"" + repeat('a', 64)
                                + "\",\"reason\":\"revise\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/retry")
                        .header("Idempotency-Key", "retry-key"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"stop\"}"))
                .andExpect(status().isAccepted());

        verify(appService).startStage("run-1", com.example.agentweb.domain.harness.HarnessStage.ANALYSIS,
                "start-key");
        verify(appService).approve(eq("run-1"), any(), eq(repeat('a', 64)), eq("ok"));
    }

    @Test
    void domain_not_found_and_invalid_stage_should_map_to_404_and_400() throws Exception {
        when(queryService.findById("missing")).thenReturn(Optional.<HarnessRunView>empty());

        mvc.perform(get("/api/harness/runs/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HARNESS_RUN_NOT_FOUND"));
        mvc.perform(post("/api/harness/runs/run-1/stages/UNKNOWN/start")
                        .header("Idempotency-Key", "start-key"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void illegal_domain_transition_should_map_to_explicit_409() throws Exception {
        when(appService.startStage(eq("run-1"), any(), eq("start-key")))
                .thenThrow(new IllegalHarnessTransitionException("previous stage must pass"));

        mvc.perform(post("/api/harness/runs/run-1/stages/DESIGN/start")
                        .header("Idempotency-Key", "start-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HARNESS_ILLEGAL_TRANSITION"));
    }

    private HarnessRunView emptyView() {
        return new HarnessRunView("run-1", "M1", "/workspace", "CODEX", "local",
                "harness@1.0.0", "DRAFT", "admin", 0L, 0L, 0L,
                Collections.<HarnessRunView.StageView>emptyList(),
                Collections.<HarnessRunView.ArtifactView>emptyList(),
                Collections.<HarnessRunView.GateView>emptyList(),
                Collections.<HarnessRunView.ApprovalView>emptyList(),
                Collections.<HarnessRunView.EventView>emptyList());
    }

    private String repeat(char value, int count) {
        return String.join("", Collections.nCopies(count, String.valueOf(value)));
    }
}
