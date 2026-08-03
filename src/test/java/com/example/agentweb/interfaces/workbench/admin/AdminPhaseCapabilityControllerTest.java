package com.example.agentweb.interfaces.workbench.admin;

import com.example.agentweb.app.workbench.admin.AdminCapabilityCatalogView;
import com.example.agentweb.app.workbench.admin.AdminPhaseCapabilityAppService;
import com.example.agentweb.app.workbench.admin.AdminPhaseCapabilityProfileView;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.auth.UserRole;
import com.example.agentweb.domain.workbench.WorkbenchAdministrator;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminPhaseCapabilityController 的接口边界测试。
 *
 * @author alex
 * @since 2026-08-02
 */
class AdminPhaseCapabilityControllerTest {

    private AdminPhaseCapabilityAppService appService;
    private UserContext userContext;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        appService = mock(AdminPhaseCapabilityAppService.class);
        userContext = mock(UserContext.class);
        when(userContext.currentUser()).thenReturn(Optional.of(
                new LoginUser("admin-1", "Admin", null, UserRole.ADMIN)));
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminPhaseCapabilityController(
                                appService, userContext))
                .setControllerAdvice(new AdminWorkbenchExceptionHandler())
                .build();
    }

    @Test
    void shouldListAllProfiles() throws Exception {
        when(appService.listProfiles()).thenReturn(List.of(
                createProfileView(WorkbenchPhase.REQUIREMENT_ANALYSIS)));
        mvc.perform(get("/api/admin/workbench-capabilities/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].phase")
                        .value("REQUIREMENT_ANALYSIS"));
    }

    @Test
    void shouldGetSingleProfile() throws Exception {
        when(appService.getProfile(WorkbenchPhase.SOLUTION_DESIGN))
                .thenReturn(createProfileView(WorkbenchPhase.SOLUTION_DESIGN));
        mvc.perform(get(
                "/api/admin/workbench-capabilities/profiles/SOLUTION_DESIGN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SOLUTION_DESIGN"));
    }

    @Test
    void shouldUpdateProfileWithIfMatch() throws Exception {
        AdminPhaseCapabilityProfileView view =
                createProfileView(WorkbenchPhase.REQUIREMENT_ANALYSIS);
        when(appService.updateProfile(
                eq(WorkbenchPhase.REQUIREMENT_ANALYSIS), any(), eq(1L),
                any(WorkbenchAdministrator.class)))
                .thenReturn(view);
        mvc.perform(put(
                "/api/admin/workbench-capabilities/profiles/REQUIREMENT_ANALYSIS")
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capabilities":[
                                    {"id":"platform/workbench-safety","type":"RULE","required":true},
                                    {"id":"test-skill","type":"SKILL","required":false}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("REQUIREMENT_ANALYSIS"));
    }

    @Test
    void shouldRejectUpdateWithoutIfMatch() throws Exception {
        mvc.perform(put(
                "/api/admin/workbench-capabilities/profiles/REQUIREMENT_ANALYSIS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capabilities":[
                                    {"id":"x","type":"RULE","required":true}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnConflictOnVersionMismatch() throws Exception {
        when(appService.updateProfile(
                eq(WorkbenchPhase.REQUIREMENT_ANALYSIS), any(), anyLong(),
                any(WorkbenchAdministrator.class)))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.VERSION_CONFLICT,
                        "stale version"));
        mvc.perform(put(
                "/api/admin/workbench-capabilities/profiles/REQUIREMENT_ANALYSIS")
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capabilities":[
                                    {"id":"x","type":"RULE","required":true}
                                ]}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldListCatalog() throws Exception {
        when(appService.listCatalog()).thenReturn(
                AdminCapabilityCatalogView.of(
                        List.of(new AdminCapabilityCatalogView.CatalogEntry(
                                "skill-1", "1.0.0", "Test Skill",
                                List.of("CODEX", "CLAUDE"))),
                        Collections.emptyList()));
        mvc.perform(get("/api/admin/workbench-capabilities/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].id").value("skill-1"));
    }

    @Test
    void shouldRejectNonAdminUser() throws Exception {
        when(userContext.currentUser()).thenReturn(Optional.of(
                new LoginUser("user-1", "User", null, UserRole.USER)));
        mvc.perform(get("/api/admin/workbench-capabilities/profiles"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectUnauthenticatedUser() throws Exception {
        when(userContext.currentUser()).thenReturn(Optional.empty());
        mvc.perform(get("/api/admin/workbench-capabilities/profiles"))
                .andExpect(status().isUnauthorized());
    }

    private AdminPhaseCapabilityProfileView createProfileView(
            WorkbenchPhase phase) {
        return new AdminPhaseCapabilityProfileView(
                phase.name(),
                "workbench-" + phase.name().toLowerCase().replace('_', '-'),
                "1",
                "a".repeat(64),
                List.of(new AdminPhaseCapabilityProfileView
                        .CapabilityReferenceView(
                        "platform/workbench-safety", "RULE", true)),
                "admin-1",
                "Admin",
                System.currentTimeMillis(),
                1L);
    }
}
