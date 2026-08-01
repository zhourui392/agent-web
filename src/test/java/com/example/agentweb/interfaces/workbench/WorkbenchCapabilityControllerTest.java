package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.capability.CapabilityPreviewItemView;
import com.example.agentweb.app.workbench.capability.EffectivePhaseCapabilityView;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityApplicationErrorCode;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityApplicationException;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityMutationView;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityOwnerService;
import com.example.agentweb.app.workbench.capability.PublicPhaseCapabilityOverrideView;
import com.example.agentweb.app.workbench.capability.PutPhaseCapabilityOverrideCommand;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.CapabilityResolutionException;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.interfaces.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Workbench Phase Capability Owner API 的安全边界契约测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@WebMvcTest(WorkbenchCapabilityController.class)
@Import({GlobalExceptionHandler.class, WorkbenchExceptionHandler.class})
class WorkbenchCapabilityControllerTest {

    private static final String OWNER_ID = "owner-1";
    private static final String OWNER_NAME = "Alex";
    private static final String WORKBENCH_ID = "workbench-1";
    private static final String PROFILE_ROUTE =
            "/api/workbenches/{id}/phases/{phase}/capability-profile";
    private static final String OVERRIDE_ROUTE =
            "/api/workbenches/{id}/phases/{phase}/capability-override";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private PhaseCapabilityOwnerService service;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void authenticateOwner() {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(currentUserProvider.currentUserName()).thenReturn(OWNER_NAME);
    }

    @Test
    void getEffectiveProfileShouldUseOwnerAndPhaseAndReturnSafeProjection()
            throws Exception {
        EffectivePhaseCapabilityView view = effectiveProfile();
        when(service.getEffectiveProfile(any(), any(), any()))
                .thenReturn(view);

        mvc.perform(get(PROFILE_ROUTE, WORKBENCH_ID, "implement_test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("IMPLEMENT_TEST"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.profileId").value("implement-profile"))
                .andExpect(jsonPath("$.profileVersion").value("3"))
                .andExpect(jsonPath("$.profileHash").value("a".repeat(64)))
                .andExpect(jsonPath("$.rules[0].id").value("platform/safety"))
                .andExpect(jsonPath("$.rules[0].required").value(true))
                .andExpect(jsonPath("$.rules[0].selected").value(true))
                .andExpect(jsonPath("$.optionalSkillIds[0]").value("java-tdd"))
                .andExpect(jsonPath("$.optionalMcpServerIds").isEmpty())
                .andExpect(jsonPath("$.overrideVersion").value(2))
                .andExpect(jsonPath("$.effectiveFrom").value("NEXT_RUN"))
                .andExpect(jsonPath("$.activeRunSnapshotHash")
                        .value("b".repeat(64)))
                .andExpect(jsonPath("$.addedOptionalSkillIds").doesNotExist())
                .andExpect(jsonPath("$.removedOptionalSkillIds").doesNotExist())
                .andExpect(jsonPath("$.selectedOptionalRuleIds").doesNotExist())
                .andExpect(jsonPath("$.updatedBy").doesNotExist())
                .andExpect(jsonPath("$.workingDir").doesNotExist())
                .andExpect(jsonPath("$.repositoryRoot").doesNotExist())
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(content().string(not(containsString("/home/"))));

        verify(service).getEffectiveProfile(
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                WorkbenchId.of(WORKBENCH_ID),
                WorkbenchPhase.IMPLEMENT_TEST);
    }

    @Test
    void getOverrideShouldReturnPublicProjectionAndStableNotFound()
            throws Exception {
        PublicPhaseCapabilityOverrideView view =
                mock(PublicPhaseCapabilityOverrideView.class);
        when(view.getOptionalSkillIds())
                .thenReturn(Collections.singletonList("java-tdd"));
        when(view.getOptionalMcpServerIds())
                .thenReturn(Collections.<String>emptyList());
        when(view.getAdditionalRule()).thenReturn("只运行聚焦测试");
        when(view.getVersion()).thenReturn(4L);
        when(view.getUpdatedAt()).thenReturn(1_775_210_400_000L);
        when(service.getOverride(any(), any(), any()))
                .thenReturn(Optional.of(view))
                .thenReturn(Optional.<PublicPhaseCapabilityOverrideView>empty());

        mvc.perform(get(OVERRIDE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optionalSkillIds[0]").value("java-tdd"))
                .andExpect(jsonPath("$.optionalMcpServerIds").isEmpty())
                .andExpect(jsonPath("$.additionalRule").value("只运行聚焦测试"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.updatedAt").value(1_775_210_400_000L))
                .andExpect(jsonPath("$.baseProfileId").doesNotExist())
                .andExpect(jsonPath("$.updatedBy").doesNotExist());
        mvc.perform(get(OVERRIDE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_CAPABILITY_OVERRIDE_NOT_FOUND"));
    }

    @Test
    void putShouldAcceptOnlyPublicSelectionAndReturnNextRunEvidence()
            throws Exception {
        when(service.putOverride(any(), any())).thenReturn(
                PhaseCapabilityMutationView.nextRun(5L, "c".repeat(64)));

        mvc.perform(put(OVERRIDE_ROUTE, WORKBENCH_ID, "solution_design")
                        .header("If-Match", "4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionalSkillIds\":[\"architecture\"],"
                                + "\"optionalMcpServerIds\":[],"
                                + "\"additionalRule\":\"保留回滚说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.effectiveFrom").value("NEXT_RUN"))
                .andExpect(jsonPath("$.activeRunSnapshotHash")
                        .value("c".repeat(64)));

        ArgumentCaptor<PutPhaseCapabilityOverrideCommand> command =
                ArgumentCaptor.forClass(
                        PutPhaseCapabilityOverrideCommand.class);
        verify(service).putOverride(
                eq(OwnerReference.of(OWNER_ID, OWNER_NAME)),
                command.capture());
        assertEquals(WorkbenchId.of(WORKBENCH_ID),
                command.getValue().getWorkbenchId());
        assertEquals(WorkbenchPhase.SOLUTION_DESIGN,
                command.getValue().getPhase());
        assertEquals(4L, command.getValue().getExpectedVersion());
        assertEquals(Collections.singletonList("architecture"),
                command.getValue().getOptionalSkillIds());
        assertEquals(Collections.emptyList(),
                command.getValue().getOptionalMcpServerIds());
        assertEquals("保留回滚说明", command.getValue().getAdditionalRule());
    }

    @Test
    void putAgainstInitialAbsentTokenShouldReturnFirstPersistedVersionOne()
            throws Exception {
        when(service.putOverride(any(), any())).thenReturn(
                PhaseCapabilityMutationView.nextRun(1L, null));

        mvc.perform(put(OVERRIDE_ROUTE, WORKBENCH_ID, "requirement_analysis")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionalSkillIds\":[],"
                                + "\"optionalMcpServerIds\":[],"
                                + "\"additionalRule\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.effectiveFrom").value("NEXT_RUN"));
    }

    @Test
    void deleteShouldRestoreDefaultAndPreserveActiveSnapshotEvidence()
            throws Exception {
        when(service.deleteOverride(any(), any(), any(), eq(4L)))
                .thenReturn(PhaseCapabilityMutationView.nextRun(
                        5L, "d".repeat(64)));

        mvc.perform(delete(OVERRIDE_ROUTE, WORKBENCH_ID, "review_refactor")
                        .header("If-Match", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.effectiveFrom").value("NEXT_RUN"))
                .andExpect(jsonPath("$.activeRunSnapshotHash")
                        .value("d".repeat(64)));

        verify(service).deleteOverride(
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                WorkbenchId.of(WORKBENCH_ID),
                WorkbenchPhase.REVIEW_REFACTOR, 4L);
    }

    @Test
    void invalidHeaderPhaseBodyAndInternalFieldsShouldFailBeforeMutation()
            throws Exception {
        String valid = "{\"optionalSkillIds\":[],"
                + "\"optionalMcpServerIds\":[],\"additionalRule\":\"\"}";
        mvc.perform(put(OVERRIDE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_CAPABILITY_REQUEST_INVALID"));
        mvc.perform(put(OVERRIDE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "not-a-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid))
                .andExpect(status().isBadRequest());
        mvc.perform(put(OVERRIDE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid))
                .andExpect(status().isBadRequest());
        mvc.perform(put(OVERRIDE_ROUTE, WORKBENCH_ID, "unknown")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid))
                .andExpect(status().isBadRequest());
        mvc.perform(put(OVERRIDE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionalSkillIds\":[],"
                                + "\"optionalMcpServerIds\":[],"
                                + "\"additionalRule\":\"\","
                                + "\"removedOptionalSkillIds\":[\"required\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_CAPABILITY_REQUEST_INVALID"));

        verify(service, never()).putOverride(any(), any());
    }

    @Test
    void ownerVisibilityArchiveAndApplicationFailuresShouldUseStableCodes()
            throws Exception {
        String request = "{\"optionalSkillIds\":[],"
                + "\"optionalMcpServerIds\":[],\"additionalRule\":\"\"}";
        when(service.getEffectiveProfile(any(), any(), any()))
                .thenThrow(new WorkbenchNotFoundException());
        when(service.putOverride(any(), any()))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.ARCHIVED, "archived"))
                .thenThrow(new PhaseCapabilityApplicationException(
                        PhaseCapabilityApplicationErrorCode.VERSION_CONFLICT,
                        "conflict"))
                .thenThrow(new PhaseCapabilityApplicationException(
                        PhaseCapabilityApplicationErrorCode.ESCALATION_DENIED,
                        "denied"));

        mvc.perform(get(PROFILE_ROUTE, "foreign", "IMPLEMENT_TEST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKBENCH_NOT_FOUND"));
        mvc.perform(put(OVERRIDE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WORKBENCH_ARCHIVED"));
        mvc.perform(put(OVERRIDE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_CAPABILITY_VERSION_CONFLICT"));
        mvc.perform(put(OVERRIDE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_CAPABILITY_ESCALATION_DENIED"));
    }

    @Test
    void resolutionAndProfileFailuresShouldUseProductStatusContract()
            throws Exception {
        when(service.getEffectiveProfile(any(), any(), any()))
                .thenThrow(new CapabilityResolutionException(
                        "WORKBENCH_CAPABILITY_REQUIRED_UNAVAILABLE", "missing"))
                .thenThrow(new CapabilityResolutionException(
                        "WORKBENCH_RUNTIME_CAPABILITY_INCOMPATIBLE", "runtime"))
                .thenThrow(new CapabilityResolutionException(
                        "CAPABILITY_CATALOG_INVALID", "catalog"))
                .thenThrow(new CapabilityCatalogException(
                        "PHASE_PROFILE_SET_INCOMPLETE", "profile"));

        mvc.perform(get(PROFILE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_CAPABILITY_REQUIRED_UNAVAILABLE"));
        mvc.perform(get(PROFILE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_RUNTIME_CAPABILITY_INCOMPATIBLE"));
        mvc.perform(get(PROFILE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_PROFILE_UNAVAILABLE"));
        mvc.perform(get(PROFILE_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_PROFILE_UNAVAILABLE"));
    }

    private EffectivePhaseCapabilityView effectiveProfile() {
        EffectivePhaseCapabilityView view =
                mock(EffectivePhaseCapabilityView.class);
        CapabilityPreviewItemView rule =
                mock(CapabilityPreviewItemView.class);
        when(rule.getId()).thenReturn("platform/safety");
        when(rule.isRequired()).thenReturn(true);
        when(rule.isSelected()).thenReturn(true);
        when(rule.getSource()).thenReturn("PLATFORM");
        when(rule.getSummary()).thenReturn("强制安全规则");
        when(view.getPhase()).thenReturn(WorkbenchPhase.IMPLEMENT_TEST);
        when(view.getStatus()).thenReturn("AVAILABLE");
        when(view.getProfileId()).thenReturn("implement-profile");
        when(view.getProfileVersion()).thenReturn("3");
        when(view.getProfileHash()).thenReturn("a".repeat(64));
        when(view.getRules()).thenReturn(Collections.singletonList(rule));
        when(view.getSkills()).thenReturn(Collections.<CapabilityPreviewItemView>emptyList());
        when(view.getMcpServers())
                .thenReturn(Collections.<CapabilityPreviewItemView>emptyList());
        when(view.getOptionalSkillIds())
                .thenReturn(Collections.singletonList("java-tdd"));
        when(view.getOptionalMcpServerIds())
                .thenReturn(Collections.<String>emptyList());
        when(view.getAdditionalRule()).thenReturn("聚焦测试");
        when(view.getOverrideVersion()).thenReturn(2L);
        when(view.getWarnings()).thenReturn(Collections.<String>emptyList());
        when(view.getEffectiveFrom()).thenReturn("NEXT_RUN");
        when(view.getActiveRunSnapshotHash()).thenReturn("b".repeat(64));
        return view;
    }
}
