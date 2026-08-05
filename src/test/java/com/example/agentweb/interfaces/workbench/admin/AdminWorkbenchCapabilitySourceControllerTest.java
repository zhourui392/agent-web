package com.example.agentweb.interfaces.workbench.admin;

import com.example.agentweb.app.capability.CapabilitySourceConfigurationAppService;
import com.example.agentweb.app.capability.CapabilitySourceProbeResult;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.auth.UserRole;
import com.example.agentweb.domain.capability.CapabilityConfigurationEditor;
import com.example.agentweb.domain.capability.CapabilitySourceConfiguration;
import com.example.agentweb.domain.capability.CapabilitySourceVersionConflictException;
import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Workbench Capability Source 管理接口测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class AdminWorkbenchCapabilitySourceControllerTest {

    private CapabilitySourceConfigurationAppService appService;
    private UserContext userContext;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        appService = mock(CapabilitySourceConfigurationAppService.class);
        userContext = mock(UserContext.class);
        when(userContext.currentUser()).thenReturn(Optional.of(
                new LoginUser("admin-1", "Admin", null, UserRole.ADMIN)));
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminWorkbenchCapabilitySourceController(
                                appService, userContext, new ObjectMapper()))
                .setControllerAdvice(new AdminWorkbenchExceptionHandler())
                .build();
    }

    @Test
    void should_GetCurrentConfiguration_When_AdminIsAuthenticated() throws Exception {
        // Given
        when(appService.find()).thenReturn(Optional.of(configuration()));

        // When / Then
        mvc.perform(get("/api/admin-settings/workbench/capability-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.commandCatalogDirectories[0].directoryIdentifier")
                        .value("commands"))
                .andExpect(jsonPath("$.mcpConfiguration.schema")
                        .value("workbench-mcp-catalog@1"));
    }

    @Test
    void should_ValidateWithoutPersisting_When_CandidateIsSubmitted() throws Exception {
        // Given
        when(appService.validate(any())).thenReturn(new CapabilitySourceProbeResult(
                Collections.emptyList(), Collections.emptyList(), emptyMcp(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList()));

        // When / Then
        mvc.perform(post("/api/admin-settings/workbench/capability-sources/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalMcpConfigurationJson").exists());
        verify(appService).validate(any());
    }

    @Test
    void should_UpdateWithIfMatchAndRealAdministrator() throws Exception {
        // Given
        when(appService.update(any(), eq(1L), any(CapabilityConfigurationEditor.class)))
                .thenReturn(configuration());

        // When / Then
        mvc.perform(put("/api/admin-settings/workbench/capability-sources")
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedBy.actorId").value("admin-1"));
        verify(appService).update(any(), eq(1L),
                eq(CapabilityConfigurationEditor.create("admin-1", "Admin")));
    }

    @Test
    void should_RejectMissingVersionAndNonAdmin() throws Exception {
        // Given / When / Then
        mvc.perform(put("/api/admin-settings/workbench/capability-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isBadRequest());

        when(userContext.currentUser()).thenReturn(Optional.of(
                new LoginUser("user-1", "User", null, UserRole.USER)));
        mvc.perform(get("/api/admin-settings/workbench/capability-sources"))
                .andExpect(status().isForbidden());
    }

    @Test
    void should_ReturnConflict_When_ConfigurationVersionIsStale() throws Exception {
        // Given
        when(appService.update(any(), eq(1L), any(CapabilityConfigurationEditor.class)))
                .thenThrow(new CapabilitySourceVersionConflictException(1L, 2L));

        // When / Then
        mvc.perform(put("/api/admin-settings/workbench/capability-sources")
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_CAPABILITY_SOURCE_VERSION_CONFLICT"));
    }

    private CapabilitySourceConfiguration configuration() {
        return CapabilitySourceConfiguration.create(
                Collections.singletonList(CommandCatalogDirectory.create(
                        "commands", "/opt/agent/commands", true)),
                Collections.emptyList(), emptyMcp(),
                CapabilityConfigurationEditor.create("admin-1", "Admin"),
                Instant.parse("2026-08-05T08:00:00Z"));
    }

    private String requestJson() {
        return "{\"commandCatalogDirectories\":[{"
                + "\"directoryIdentifier\":\"commands\","
                + "\"absoluteDirectory\":\"/opt/agent/commands\","
                + "\"enabled\":true}],\"skillCatalogDirectories\":[],"
                + "\"mcpConfiguration\":{\"schema\":\"workbench-mcp-catalog@1\","
                + "\"servers\":[]}}";
    }

    private String emptyMcp() {
        return "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[]}";
    }
}
