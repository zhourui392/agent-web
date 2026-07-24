package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.HarnessAppService;
import com.example.agentweb.app.harness.DeploymentExecutionQueryService;
import com.example.agentweb.app.harness.DeploymentExecutionView;
import com.example.agentweb.app.harness.HarnessDeploymentResult;
import com.example.agentweb.app.harness.HarnessDeploymentReadinessQueryService;
import com.example.agentweb.app.harness.HarnessDeploymentReadinessView;
import com.example.agentweb.app.harness.HarnessDeploymentService;
import com.example.agentweb.app.harness.HarnessArtifactContentView;
import com.example.agentweb.app.harness.HarnessArtifactQueryService;
import com.example.agentweb.app.harness.HarnessMutationResult;
import com.example.agentweb.app.harness.HarnessExecutionService;
import com.example.agentweb.app.harness.HarnessRunQueryService;
import com.example.agentweb.app.harness.HarnessRunSummaryView;
import com.example.agentweb.app.harness.HarnessRunView;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.IllegalHarnessTransitionException;
import com.example.agentweb.domain.harness.DeploymentReadiness;
import com.example.agentweb.domain.harness.DeploymentExecutionIdempotencyConflictException;
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
import static org.mockito.Mockito.never;
import static org.hamcrest.Matchers.containsString;
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

    @MockBean
    private HarnessExecutionService executionService;

    @MockBean
    private HarnessDeploymentService deploymentService;

    @MockBean
    private HarnessDeploymentReadinessQueryService deploymentReadinessQueryService;

    @MockBean
    private DeploymentExecutionQueryService deploymentQueryService;

    @MockBean
    private HarnessArtifactQueryService artifactQueryService;

    @Test
    void create_and_get_should_expose_management_contract() throws Exception {
        when(appService.create(any())).thenReturn(
                HarnessMutationResult.of("run-1", "DRAFT", 0L, false));
        when(queryService.findById("run-1")).thenReturn(Optional.of(emptyView()));

        mvc.perform(post("/api/harness/runs")
                        .header("Idempotency-Key", "create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"M1\",\"workingDir\":\"/workspace\","
                                + "\"agentType\":\"CODEX\",\"environment\":\"local\","
                                + "\"originalRequirement\":\"Implement M4\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/harness/runs/run-1"))
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mvc.perform(get("/api/harness/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"));
    }

    @Test
    void list_should_expose_management_projection() throws Exception {
        when(queryService.list()).thenReturn(Collections.singletonList(
                new HarnessRunSummaryView("run-1", "M4", "ACTIVE", "local", "admin", 123L)));

        mvc.perform(get("/api/harness/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value("run-1"))
                .andExpect(jsonPath("$[0].title").value("M4"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        verify(queryService).list();
    }

    @Test
    void missing_or_oversized_idempotency_key_should_return_explicit_400() throws Exception {
        mvc.perform(post("/api/harness/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"M1\",\"workingDir\":\"/workspace\","
                                + "\"agentType\":\"CODEX\",\"environment\":\"local\","
                                + "\"originalRequirement\":\"Implement M4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HARNESS_IDEMPOTENCY_KEY"));

        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/start")
                        .header("Idempotency-Key", String.join("", Collections.nCopies(129, "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HARNESS_IDEMPOTENCY_KEY"));
    }

    @Test
    void create_should_reject_missing_original_requirement() throws Exception {
        mvc.perform(post("/api/harness/runs")
                        .header("Idempotency-Key", "create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"M4\",\"workingDir\":\"/workspace\","
                                + "\"agentType\":\"CODEX\",\"environment\":\"local\"}"))
                .andExpect(status().isBadRequest());
        verify(appService, never()).create(any());
    }

    @Test
    void stage_artifact_gate_approval_retry_and_cancel_actions_should_delegate() throws Exception {
        HarnessMutationResult result = HarnessMutationResult.of("run-1", "ACTIVE", 1L, false);
        when(appService.startStage(any(), any(), any())).thenReturn(result);
        when(appService.registerArtifact(any())).thenReturn(result);
        when(appService.evaluateGate(any(), any(), any())).thenReturn(result);
        when(appService.requestApproval(any(), any())).thenReturn(result);
        when(appService.approve(any(), any(), any(), any(), any())).thenReturn(result);
        when(appService.reject(any(), any(), any(), any(), any())).thenReturn(result);
        when(appService.requestInput(any(), any(), any(), any(), anyBoolean())).thenReturn(result);
        when(appService.answerQuestion(any(), any(), any())).thenReturn(result);
        when(appService.approveDeployment(any(), any(), any(), any())).thenReturn(result);
        when(appService.retryStage(any(), any(), any())).thenReturn(result);
        when(executionService.cancel(any(), any())).thenReturn(result);

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
                        .content("{\"rule\":\"artifact-schema-valid\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionId\":\"question-1\",\"question\":\"Which tenant?\","
                                + "\"blocking\":true}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/questions/question-1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"tenant-a\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/request-approval"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/approve")
                        .header("Idempotency-Key", "approve-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artifactBaselineHash\":\"" + repeat('a', 64)
                                + "\",\"reason\":\"ok\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/reject")
                        .header("Idempotency-Key", "reject-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artifactBaselineHash\":\"" + repeat('a', 64)
                                + "\",\"reason\":\"revise\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/run-1/stages/DEPLOYMENT/deployment-approval")
                        .header("Idempotency-Key", "deploy-approval-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputBaselineHash\":\"" + repeat('b', 64)
                                + "\",\"reason\":\"deploy local\"}"))
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
        verify(appService).evaluateGate("run-1",
                com.example.agentweb.domain.harness.HarnessStage.ANALYSIS,
                "artifact-schema-valid");
        verify(appService).approve(eq("run-1"), any(), eq(repeat('a', 64)), eq("ok"),
                eq("approve-key"));
        verify(appService).approveDeployment("run-1", repeat('b', 64),
                "deploy local", "deploy-approval-key");
    }

    @Test
    void deployment_start_query_and_reconcile_should_expose_controlled_contract() throws Exception {
        HarnessDeploymentResult result = new HarnessDeploymentResult(
                "deploy-1", "run-1", "PREPARED", false);
        DeploymentExecutionView view = new DeploymentExecutionView(
                "deploy-1", "run-1", 1, "RECONCILIATION_REQUIRED", repeat('a', 64),
                "local-default", "1", repeat('b', 64), "application restarted",
                1L, null, null);
        when(deploymentService.start(any())).thenReturn(result);
        when(deploymentReadinessQueryService.find("run-1"))
                .thenReturn(new HarnessDeploymentReadinessView(new DeploymentReadiness(
                        "run-1", "local", 1, repeat('a', 64), true)));
        when(deploymentQueryService.listByRun("run-1"))
                .thenReturn(Collections.singletonList(view));
        when(deploymentQueryService.find("run-1", "deploy-1")).thenReturn(Optional.of(view));
        when(deploymentService.reconcileAsFailed("run-1", "deploy-1", "verified failed"))
                .thenReturn(new HarnessDeploymentResult(
                        "deploy-1", "run-1", "FAILED", false));

        mvc.perform(post("/api/harness/runs/run-1/stages/DEPLOYMENT/deployments")
                        .header("Idempotency-Key", "deploy-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"local-default\","
                                + "\"approvedInputBaselineHash\":\"" + repeat('a', 64) + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value("deploy-1"));
        mvc.perform(get("/api/harness/runs/run-1/stages/DEPLOYMENT/deployment-readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputBaselineHash").value(repeat('a', 64)))
                .andExpect(jsonPath("$.approved").value(true));
        mvc.perform(get("/api/harness/runs/run-1/deployments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RECONCILIATION_REQUIRED"));
        mvc.perform(get("/api/harness/runs/run-1/deployments/deploy-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateId").value("local-default"));
        mvc.perform(post("/api/harness/runs/run-1/deployments/deploy-1/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"verified failed\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void events_artifact_download_and_final_report_should_be_read_only_projections()
            throws Exception {
        when(queryService.findById("run-1")).thenReturn(Optional.of(emptyView()));
        HarnessArtifactContentView artifact = new HarnessArtifactContentView(
                "requirements", 1, "REQUIREMENT", "application/json", repeat('a', 64),
                "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HarnessArtifactContentView report = new HarnessArtifactContentView(
                "final-report", 1, "FINAL_REPORT", "application/json", repeat('b', 64),
                "{\"status\":\"SUCCEEDED\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(artifactQueryService.findLatest("run-1", "requirements"))
                .thenReturn(Optional.of(artifact));
        when(artifactQueryService.findFinalReport("run-1")).thenReturn(Optional.of(report));

        mvc.perform(get("/api/harness/runs/run-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/harness/runs/run-1/artifacts/requirements"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(header().string("ETag", "\"" + repeat('a', 64) + "\""))
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        containsString("requirement-v1.json")));
        mvc.perform(get("/api/harness/runs/run-1/report"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void missing_artifact_report_deployment_and_event_run_should_return_explicit_404()
            throws Exception {
        when(queryService.findById("missing")).thenReturn(Optional.<HarnessRunView>empty());

        mvc.perform(get("/api/harness/runs/run-1/artifacts/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HARNESS_ARTIFACT_NOT_FOUND"));
        mvc.perform(get("/api/harness/runs/run-1/report"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HARNESS_ARTIFACT_NOT_FOUND"));
        mvc.perform(get("/api/harness/runs/run-1/deployments/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("HARNESS_DEPLOYMENT_EXECUTION_NOT_FOUND"));
        mvc.perform(get("/api/harness/runs/missing/events"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HARNESS_RUN_NOT_FOUND"));
    }

    @Test
    void invalid_deployment_hash_and_template_should_be_rejected_at_interface_boundary()
            throws Exception {
        mvc.perform(post("/api/harness/runs/run-1/stages/DEPLOYMENT/deployments")
                        .header("Idempotency-Key", "deploy-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"../evil\","
                                + "\"approvedInputBaselineHash\":\"" + repeat('a', 64) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/harness/runs/run-1/stages/DEPLOYMENT/deployments")
                        .header("Idempotency-Key", "deploy-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"local-default\","
                                + "\"approvedInputBaselineHash\":\"not-a-hash\"}"))
                .andExpect(status().isBadRequest());
        verify(deploymentService, never()).start(any());
    }

    @Test
    void deployment_idempotency_conflict_and_invalid_reconciliation_should_return_409()
            throws Exception {
        when(deploymentService.start(any()))
                .thenThrow(new DeploymentExecutionIdempotencyConflictException());
        when(deploymentService.reconcileAsFailed("run-1", "deploy-1", "not uncertain"))
                .thenThrow(new IllegalHarnessTransitionException(
                        "deployment execution does not require reconciliation"));

        mvc.perform(post("/api/harness/runs/run-1/stages/DEPLOYMENT/deployments")
                        .header("Idempotency-Key", "deploy-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"local-default\","
                                + "\"approvedInputBaselineHash\":\"" + repeat('a', 64) + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("HARNESS_DEPLOYMENT_IDEMPOTENCY_CONFLICT"));
        mvc.perform(post("/api/harness/runs/run-1/deployments/deploy-1/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not uncertain\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HARNESS_ILLEGAL_TRANSITION"));
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
