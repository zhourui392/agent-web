package com.example.agentweb.interfaces;

import com.example.agentweb.app.requirement.RequirementAppService;
import com.example.agentweb.app.requirement.RequirementDetail;
import com.example.agentweb.app.requirement.RequirementQueryService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.requirement.ApprovalNotAllowedException;
import com.example.agentweb.domain.requirement.IllegalRequirementTransitionException;
import com.example.agentweb.domain.requirement.RequirementAction;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementQuotaExceededException;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.domain.requirement.RequirementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice：DTO 校验、状态码矩阵、错误码映射、actor 取自 UserContext 而非请求体。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@WebMvcTest(RequirementController.class)
@Import({GlobalExceptionHandler.class, RequirementExceptionAdvice.class})
@TestPropertySource(properties = "agent.requirement.enabled=true")
public class RequirementControllerTest {

    private static final String USER = "test-user";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RequirementAppService appService;
    @MockBean
    private RequirementQueryService queryService;
    @MockBean
    private CurrentUserProvider currentUserProvider;

    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;
    /** ApiKeyAuthFilter 构造依赖, 切片上下文必须提供。 */
    @MockBean
    private com.example.agentweb.infra.auth.ApiKeyProperties apiKeyProperties;
    @MockBean
    private com.example.agentweb.infra.auth.AuthProperties authProperties;
    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @BeforeEach
    void stubAuth() {
        when(currentUserProvider.currentUserId()).thenReturn(USER);
    }

    @Test
    public void create_should_use_user_context_as_owner_and_return_201() throws Exception {
        when(appService.create(eq("标题"), eq("描述"), eq(USER), eq(RequirementSource.BOARD)))
                .thenReturn("R2607040001");

        mvc.perform(post("/api/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"标题\",\"description\":\"描述\",\"owner\":\"V-forged\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("R2607040001"));

        // owner 取 UserContext, 请求体伪造的 owner 不生效
        verify(appService).create("标题", "描述", USER, RequirementSource.BOARD);
    }

    @Test
    public void create_over_quota_should_return_409_with_code() throws Exception {
        when(appService.create(anyString(), any(), anyString(), any()))
                .thenThrow(new RequirementQuotaExceededException(USER, 5, 5));

        mvc.perform(post("/api/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    public void illegal_transition_should_return_409_with_from_and_action() throws Exception {
        doThrow(new IllegalRequirementTransitionException(RequirementStatus.INTAKE, RequirementAction.APPROVE))
                .when(appService).approve("R1", USER);

        mvc.perform(post("/api/requirements/R1/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"))
                .andExpect(jsonPath("$.from").value("INTAKE"))
                .andExpect(jsonPath("$.action").value("APPROVE"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    public void approval_forbidden_should_return_409_with_code() throws Exception {
        doThrow(new ApprovalNotAllowedException(USER)).when(appService).approve("R1", USER);

        mvc.perform(post("/api/requirements/R1/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_FORBIDDEN"));
    }

    @Test
    public void not_found_should_return_404() throws Exception {
        doThrow(new RequirementNotFoundException("R-x")).when(appService).approve("R-x", USER);
        when(queryService.getDetail("R-y")).thenReturn(null);

        mvc.perform(post("/api/requirements/R-x/approve"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUIREMENT_NOT_FOUND"));
        mvc.perform(get("/api/requirements/R-y"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void blank_title_should_return_400() throws Exception {
        when(appService.create(any(), any(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("title required"));

        mvc.perform(post("/api/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void list_and_detail_and_events_should_delegate_to_query_service() throws Exception {
        when(queryService.listBoard(null, null)).thenReturn(Collections.emptyList());
        when(queryService.getDetail("R1")).thenReturn(new RequirementDetail(
                "R1", "t", "d", "INTAKE", null, "BOARD", null, USER,
                List.of(), null, null, null, 1L, 2L));
        when(queryService.listEvents("R1")).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/requirements")).andExpect(status().isOk());
        mvc.perform(get("/api/requirements/R1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("R1"));
        mvc.perform(get("/api/requirements/R1/events")).andExpect(status().isOk());
    }

    @Test
    public void action_endpoints_should_pass_actor_from_user_context() throws Exception {
        mvc.perform(post("/api/requirements/R1/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planText\":\"p\"}"))
                .andExpect(status().isOk());
        verify(appService).attachPlan("R1", "p", USER);

        mvc.perform(post("/api/requirements/R1/reject-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"r\"}"))
                .andExpect(status().isOk());
        verify(appService).rejectPlan("R1", USER, "r");

        mvc.perform(post("/api/requirements/R1/suspend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"r\"}"))
                .andExpect(status().isOk());
        verify(appService).suspend("R1", USER, "r");

        mvc.perform(post("/api/requirements/R1/resume")).andExpect(status().isOk());
        verify(appService).resume("R1", USER);

        mvc.perform(post("/api/requirements/R1/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"r\"}"))
                .andExpect(status().isOk());
        verify(appService).archive("R1", USER, "r");

        mvc.perform(post("/api/requirements/R1/start-implement")).andExpect(status().isOk());
        verify(appService).startImplement("R1", USER);

        mvc.perform(post("/api/requirements/R1/start-verify")).andExpect(status().isOk());
        verify(appService).startVerify("R1", USER);

        mvc.perform(post("/api/requirements/R1/deliver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mrRef\":\"https://gitlab/mr/1\"}"))
                .andExpect(status().isOk());
        verify(appService).markDelivered("R1", USER, "https://gitlab/mr/1");

        mvc.perform(post("/api/requirements/R1/request-changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"r\"}"))
                .andExpect(status().isOk());
        verify(appService).requestChanges("R1", USER, "r");
    }
}
