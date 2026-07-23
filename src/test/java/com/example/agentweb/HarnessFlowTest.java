package com.example.agentweb;

import com.example.agentweb.domain.auth.CurrentUserProvider;
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

        MvcResult created = mvc.perform(post("/api/harness/runs")
                        .header("Idempotency-Key", "flow-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Harness M1 Flow\",\"workingDir\":\""
                                + jsonEscape(WORKSPACE.toString())
                                + "\",\"agentType\":\"CODEX\",\"environment\":\"local\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String runId = objectMapper.readTree(created.getResponse().getContentAsString()).path("runId").asText();

        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/start")
                        .header("Idempotency-Key", "flow-start"))
                .andExpect(status().isAccepted());

        List<String> artifactTypes = Arrays.asList(
                "REQUIREMENT", "ACCEPTANCE_CRITERIA", "IMPACT_ANALYSIS", "OPEN_QUESTIONS");
        for (String type : artifactTypes) {
            mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/artifacts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"artifactType\":\"" + type + "\",\"content\":\""
                                    + type + " body\",\"contentType\":\"text/markdown\","
                                    + "\"classification\":\"INTERNAL\"}"))
                    .andExpect(status().isCreated());
        }

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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artifactBaselineHash\":\"" + baselineHash
                                + "\",\"reason\":\"approved\"}"))
                .andExpect(status().isAccepted());

        mvc.perform(get("/api/harness/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stages[0].status").value("PASSED"))
                .andExpect(jsonPath("$.artifacts.length()").value(4))
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

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
