package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.CapabilitySnapshotQueryService;
import com.example.agentweb.app.harness.CapabilitySnapshotView;
import com.example.agentweb.app.harness.HarnessCapabilityService;
import com.example.agentweb.app.harness.ResolveHarnessCapabilityCommand;
import com.example.agentweb.domain.harness.CapabilityResolutionException;
import com.example.agentweb.domain.harness.CapabilityAccess;
import com.example.agentweb.domain.harness.McpCapability;
import com.example.agentweb.domain.harness.McpCapabilityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Capability Snapshot 管理 API 参数、状态码和错误映射测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(HarnessCapabilityController.class)
@TestPropertySource(properties = "agent.harness.enabled=true")
@Import(GlobalExceptionHandler.class)
class HarnessCapabilityControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private HarnessCapabilityService capabilityService;

    @MockBean
    private CapabilitySnapshotQueryService queryService;

    @Test
    void resolveShouldConvertSelectionAndGrantRequestThenReturnSnapshotPreview() throws Exception {
        CapabilitySnapshotView view = view();
        when(capabilityService.resolve(any())).thenReturn(view);

        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/capability-snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"explicitSkillIds\":[\"domain-modeling-audit\"],"
                                + "\"technicalTags\":[\"java\"],"
                                + "\"approvedWorkspaceSkillIds\":[\"workspace-audit\"],"
                                + "\"readableFileRoots\":[\"workspace\"],"
                                + "\"writableFileRoots\":[],"
                                + "\"executableCommands\":[\"mvn-test\"],"
                                + "\"explicitMcpServerIds\":[\"reader\"],"
                                + "\"requiredMcpServerIds\":[\"reader\"],"
                                + "\"grantedMcpServerIds\":[\"reader\"],"
                                + "\"upstreamArtifacts\":\"approved upstream\","
                                + "\"currentInput\":\"current input\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.snapshotHash").value(repeat('a', 64)));

        ArgumentCaptor<ResolveHarnessCapabilityCommand> captor =
                ArgumentCaptor.forClass(ResolveHarnessCapabilityCommand.class);
        verify(capabilityService).resolve(captor.capture());
        assertEquals(Collections.singleton("domain-modeling-audit"), captor.getValue().getExplicitSkillIds());
        assertEquals(Collections.singleton("java"), captor.getValue().getTechnicalTags());
        assertTrue(captor.getValue().getCapabilityGrant().getReadableFileRoots().contains("workspace"));
        assertTrue(captor.getValue().getCapabilityGrant().getExecutableCommands().contains("mvn-test"));
        assertEquals(Collections.singleton("reader"),
                captor.getValue().getExplicitMcpServerIds());
        assertEquals(Collections.singleton("reader"),
                captor.getValue().getRequiredMcpServerIds());
        assertEquals(Collections.singleton("reader"),
                captor.getValue().getGrantedMcpServerIds());
    }

    @Test
    void getShouldReturnSnapshotAndMissingSnapshotShouldMapTo404() throws Exception {
        CapabilitySnapshotView existing = view();
        when(queryService.find("run-1", com.example.agentweb.domain.harness.HarnessStage.ANALYSIS, 1))
                .thenReturn(Optional.of(existing));
        when(queryService.find("missing", com.example.agentweb.domain.harness.HarnessStage.ANALYSIS, 1))
                .thenReturn(Optional.empty());

        mvc.perform(get("/api/harness/runs/run-1/stages/ANALYSIS/attempts/1/capability-snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promptHash").value(repeat('b', 64)))
                .andExpect(jsonPath("$.selectedMcpServers[0].id").value("reader"))
                .andExpect(jsonPath("$.selectedMcpServers[0].command").doesNotExist())
                .andExpect(jsonPath("$.selectedMcpServers[0].secretReferences").doesNotExist())
                .andExpect(content().string(not(containsString("secret-value-never-persist"))));
        mvc.perform(get("/api/harness/runs/missing/stages/ANALYSIS/attempts/1/capability-snapshot"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HARNESS_CAPABILITY_SNAPSHOT_NOT_FOUND"));
    }

    @Test
    void failClosedResolutionAndInvalidRequestShouldMapTo422And400() throws Exception {
        when(capabilityService.resolve(any())).thenThrow(
                new CapabilityResolutionException("SKILL_CONFLICT", "selected skills conflict"));

        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/capability-snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstreamArtifacts\":\"approved\",\"currentInput\":\"input\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SKILL_CONFLICT"));

        mvc.perform(post("/api/harness/runs/run-1/stages/ANALYSIS/capability-snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstreamArtifacts\":\"\",\"currentInput\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private CapabilitySnapshotView view() {
        CapabilitySnapshotView view = mock(CapabilitySnapshotView.class);
        when(view.getRunId()).thenReturn("run-1");
        when(view.getSnapshotHash()).thenReturn(repeat('a', 64));
        when(view.getPromptHash()).thenReturn(repeat('b', 64));
        when(view.getSelectedSkills()).thenReturn(Collections.emptyList());
        when(view.getRejectedSkills()).thenReturn(Collections.emptyList());
        when(view.getCapabilityDecisions()).thenReturn(Collections.emptyList());
        when(view.getPromptParts()).thenReturn(Collections.emptyList());
        when(view.getPromptResourceHashes()).thenReturn(Collections.emptyMap());
        CapabilitySnapshotView.McpServerView mcp =
                mock(CapabilitySnapshotView.McpServerView.class);
        when(mcp.getId()).thenReturn("reader");
        when(mcp.getVersion()).thenReturn("1.0.0");
        when(mcp.getCapabilities()).thenReturn(Collections.singletonList(
                new McpCapability("search", McpCapabilityType.TOOL, CapabilityAccess.READ)));
        when(mcp.getConfigurationHash()).thenReturn(repeat('c', 64));
        when(view.getSelectedMcpServers()).thenReturn(Collections.singletonList(mcp));
        when(view.getRejectedMcpServers()).thenReturn(Collections.emptyList());
        return view;
    }

    private String repeat(char value, int count) {
        return String.join("", Collections.nCopies(count, String.valueOf(value)));
    }
}
