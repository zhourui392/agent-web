package com.example.agentweb;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 不调用 Agent 的真实 API → Domain → SQLite → Artifact Store 纵向样例。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Tag("spring-flow")
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class HarnessFlowTest {

    private static final Path ROOT = createTempRoot();
    private static final Path WORKSPACE = createWorkspace();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @MockBean
    private WorkspacePathPolicy workspacePathPolicy;

    @MockBean
    private WorkspaceBaselineGateway workspaceBaselineGateway;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + ROOT.resolve("harness-flow.db").toAbsolutePath());
        registry.add("agent.harness.enabled", () -> "true");
        registry.add("agent.harness.artifact-root",
                () -> ROOT.resolve("artifacts").toAbsolutePath().toString());
        registry.add("agent.public-access.enabled", () -> "false");
    }

    @Test
    void analysis_stage_should_complete_through_real_http_and_sqlite() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn("admin");
        when(workspacePathPolicy.requireExistingDirectory(anyString()))
                .thenReturn(WORKSPACE.toString());
        when(workspaceBaselineGateway.capture(anyString())).thenReturn(workspaceBaseline());

        MvcResult created = mvc.perform(post("/api/harness/runs")
                        .header("Idempotency-Key", "flow-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Harness M1 Flow\",\"workingDir\":\""
                                + jsonEscape(WORKSPACE.toString())
                                + "\",\"agentType\":\"CODEX\",\"environment\":\"local\","
                                + "\"originalRequirement\":\"Harness M1 flow\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String runId = objectMapper.readTree(created.getResponse().getContentAsString()).path("runId").asText();

        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/start")
                        .header("Idempotency-Key", "flow-start"))
                .andExpect(status().isAccepted());

        registerAnalysisArtifacts(runId);

        List<String> gates = Arrays.asList(
                "required-artifacts-present", "artifact-schema-valid", "requirement-ids-unique",
                "acceptance-criteria-observable", "no-blocking-open-question");
        for (String gate : gates) {
            mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/gates")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rule\":\"" + gate
                                    + "\",\"passed\":true,\"evidenceReferences\":[]}"))
                    .andExpect(status().isAccepted());
        }

        mvc.perform(post("/api/harness/runs/" + runId
                        + "/stages/ANALYSIS/request-approval"))
                .andExpect(status().isAccepted());
        MvcResult waiting = mvc.perform(get("/api/harness/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andReturn();
        JsonNode waitingView = objectMapper.readTree(waiting.getResponse().getContentAsString());
        String baselineHash = waitingView.path("stages").get(0).path("artifactBaselineHash").asText();
        assertEquals(64, baselineHash.length());

        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/approve")
                        .header("Idempotency-Key", "flow-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artifactBaselineHash\":\"" + baselineHash
                                + "\",\"reason\":\"approved\"}"))
                .andExpect(status().isAccepted());

        mvc.perform(get("/api/harness/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stages[0].status").value("PASSED"))
                .andExpect(jsonPath("$.artifacts.length()").value(5))
                .andExpect(jsonPath("$.gateResults.length()").value(5))
                .andExpect(jsonPath("$.approvals[0].valid").value(true));
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("agent-web-harness-flow-");
        } catch (Exception ex) {
            throw new IllegalStateException("could not create harness flow temp root", ex);
        }
    }

    private static Path createWorkspace() {
        try {
            return Files.createDirectories(ROOT.resolve("workspace"));
        } catch (Exception ex) {
            throw new IllegalStateException("could not create harness flow workspace", ex);
        }
    }

    private void registerAnalysisArtifacts(String runId) throws Exception {
        registerArtifact(runId, "REQUIREMENT", "# REQ-1 Harness flow", "text/markdown");
        registerArtifact(runId, "ACCEPTANCE_CRITERIA",
                "{\"acceptanceCriteria\":[{\"id\":\"AC-1\",\"requirementId\":\"REQ-1\","
                        + "\"description\":\"flow completes\",\"verification\":\"HTTP 200\"}]}",
                "application/json");
        registerArtifact(runId, "IMPACT_ANALYSIS", "Harness flow impact", "text/markdown");
        registerArtifact(runId, "OPEN_QUESTIONS", "{\"questions\":[]}", "application/json");
    }

    private void registerArtifact(String runId, String artifactType, String content,
                                  String contentType) throws Exception {
        String request = objectMapper.createObjectNode()
                .put("artifactType", artifactType)
                .put("content", content)
                .put("contentType", contentType)
                .put("classification", "INTERNAL")
                .toString();
        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());
    }

    private WorkspaceBaseline workspaceBaseline() {
        return WorkspaceBaseline.capture(WORKSPACE.toString(), "main",
                repeat('a', 40), true, repeat('b', 64), Instant.parse("2026-07-23T00:00:00Z"));
    }

    private String repeat(char value, int count) {
        return String.join("", java.util.Collections.nCopies(count, String.valueOf(value)));
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
