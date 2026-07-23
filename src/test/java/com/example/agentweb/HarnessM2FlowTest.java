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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 不调用 Agent 的 HTTP → Domain → Catalog → SQLite 纵向快照演示。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Tag("spring-flow")
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class HarnessM2FlowTest {

    private static final Path ROOT = createTempRoot();
    private static final Path WORKSPACE = createDirectory(ROOT.resolve("workspace"));
    private static final Path PROMPT_ROOT = copyCatalog("prompt-packs");
    private static final Path SKILL_ROOT = copyCatalog("skills");

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
                () -> "jdbc:sqlite:" + ROOT.resolve("harness-m2-flow.db").toAbsolutePath());
        registry.add("agent.harness.enabled", () -> "true");
        registry.add("agent.harness.artifact-root",
                () -> ROOT.resolve("artifacts").toAbsolutePath().toString());
        registry.add("agent.harness.prompt-pack-root", () -> PROMPT_ROOT.toString());
        registry.add("agent.harness.platform-skill-root", () -> SKILL_ROOT.toString());
        registry.add("agent.public-access.enabled", () -> "false");
    }

    @Test
    void changedSkillShouldKeepOldAttemptSnapshotAndOnlyAffectNewAttempt() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn("admin");
        when(workspacePathPolicy.requireExistingDirectory(anyString())).thenReturn(WORKSPACE.toString());
        String runId = createAndStartAnalysis();

        JsonNode first = resolve(runId, "first input");
        String firstSkillHash = first.path("selectedSkills").get(0).path("packageHash").asText();
        String firstSnapshotHash = first.path("snapshotHash").asText();
        String firstPromptHash = first.path("promptHash").asText();
        assertEquals("domain-modeling-audit", first.path("selectedSkills").get(0).path("id").asText());
        assertEquals("STAGE_DEFAULT", first.path("selectedSkills").get(0).path("reason").asText());
        assertEquals(true, first.path("capabilityDecisions").get(0).path("authorized").asBoolean());

        Path skillEntry = SKILL_ROOT.resolve("domain-modeling-audit/1.0.0/SKILL.md");
        Files.write(skillEntry, "\n新增规则。\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        mvc.perform(get("/api/harness/runs/" + runId
                        + "/stages/ANALYSIS/attempts/1/capability-snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotHash").value(firstSnapshotHash))
                .andExpect(jsonPath("$.selectedSkills[0].packageHash").value(firstSkillHash));

        passAndRetryAnalysis(runId);
        JsonNode second = resolve(runId, "first input");

        assertEquals(2, second.path("attemptNumber").asInt());
        assertNotEquals(firstSkillHash, second.path("selectedSkills").get(0).path("packageHash").asText());
        assertNotEquals(firstPromptHash, second.path("promptHash").asText());
        assertNotEquals(firstSnapshotHash, second.path("snapshotHash").asText());
    }

    private String createAndStartAnalysis() throws Exception {
        MvcResult created = mvc.perform(post("/api/harness/runs")
                        .header("Idempotency-Key", "m2-flow-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Harness M2 Flow\",\"workingDir\":\""
                                + jsonEscape(WORKSPACE.toString())
                                + "\",\"agentType\":\"CODEX\",\"environment\":\"test\"}"))
                .andExpect(status().isCreated()).andReturn();
        String runId = objectMapper.readTree(created.getResponse().getContentAsString()).path("runId").asText();
        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/start")
                        .header("Idempotency-Key", "m2-flow-start-1"))
                .andExpect(status().isAccepted());
        return runId;
    }

    private JsonNode resolve(String runId, String currentInput) throws Exception {
        MvcResult result = mvc.perform(post("/api/harness/runs/" + runId
                        + "/stages/ANALYSIS/capability-snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicalTags\":[\"java\"],"
                                + "\"readableFileRoots\":[\"workspace\"],"
                                + "\"upstreamArtifacts\":\"approved upstream\","
                                + "\"currentInput\":\"" + currentInput + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.promptPackVersion").value("1.0.0"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void passAndRetryAnalysis(String runId) throws Exception {
        List<String> artifacts = Arrays.asList(
                "REQUIREMENT", "ACCEPTANCE_CRITERIA", "IMPACT_ANALYSIS", "OPEN_QUESTIONS");
        for (String type : artifacts) {
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
        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/request-approval"))
                .andExpect(status().isAccepted());
        MvcResult runView = mvc.perform(get("/api/harness/runs/" + runId))
                .andExpect(status().isOk()).andReturn();
        String baselineHash = objectMapper.readTree(runView.getResponse().getContentAsString())
                .path("stages").get(0).path("artifactBaselineHash").asText();
        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artifactBaselineHash\":\"" + baselineHash
                                + "\",\"reason\":\"approved\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/harness/runs/" + runId + "/stages/ANALYSIS/retry")
                        .header("Idempotency-Key", "m2-flow-retry-2"))
                .andExpect(status().isAccepted());
    }

    private static Path copyCatalog(String name) {
        Path source = java.nio.file.Paths.get("src/main/resources/harness", name).toAbsolutePath();
        Path target = createDirectory(ROOT.resolve(name));
        try (Stream<Path> paths = Files.walk(source)) {
            paths.forEach(path -> copy(source, target, path));
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("could not copy Harness catalog " + name, ex);
        }
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path));
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("could not copy Harness catalog resource", ex);
        }
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("agent-web-harness-m2-flow-");
        } catch (IOException ex) {
            throw new IllegalStateException("could not create Harness M2 temp root", ex);
        }
    }

    private static Path createDirectory(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException("could not create Harness M2 directory", ex);
        }
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
