package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.operation.HighImpactOperationOwnerService;
import com.example.agentweb.app.workbench.operation.HighImpactOperationProjection;
import com.example.agentweb.app.workbench.operation.OperationApplicationErrorCode;
import com.example.agentweb.app.workbench.operation.OperationApplicationException;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.CommitTarget;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationDecision;
import com.example.agentweb.domain.workbench.HighImpactOperationStatus;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author alex
 * @since 2026-08-01
 */
@WebMvcTest(HighImpactOperationController.class)
@Import({GlobalExceptionHandler.class, WorkbenchExceptionHandler.class})
class HighImpactOperationControllerTest {

    private static final String OWNER_ID = "owner-1";
    private static final String OWNER_NAME = "Alex";
    private static final String WORKBENCH_ID = "workbench-1";
    private static final String DETAIL_ROUTE =
            "/api/workbenches/{workbenchId}/operations/{operationId}";
    private static final String LIST_ROUTE =
            "/api/workbenches/{workbenchId}/operations";
    private static final String DECISION_ROUTE = DETAIL_ROUTE + "/decision";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private HighImpactOperationOwnerService service;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void authenticateOwner() {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(currentUserProvider.currentUserName()).thenReturn(OWNER_NAME);
    }

    @Test
    void getShouldReturnOnlyTypedSafeOperationProjection() throws Exception {
        when(service.find(any(), any(), eq("operation-1")))
                .thenReturn(authorizedProjection());

        mvc.perform(get(DETAIL_ROUTE, WORKBENCH_ID, "operation-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value("operation-1"))
                .andExpect(jsonPath("$.sourceRunId").value("run-1"))
                .andExpect(jsonPath("$.phase").value("IMPLEMENT_TEST"))
                .andExpect(jsonPath("$.type").value("GIT_COMMIT"))
                .andExpect(jsonPath("$.target.repositoryKeys[0]").value("agent-web"))
                .andExpect(jsonPath("$.target.details.branch").value("master"))
                .andExpect(jsonPath("$.target.details.includedPaths[0]")
                        .value("README.md"))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.proposedAt").isNumber())
                .andExpect(jsonPath("$.authorizationExpiresAt").isNumber())
                .andExpect(jsonPath("$.executionAvailable").value(false))
                .andExpect(jsonPath("$.executionMode")
                        .value("MANUAL_OR_DEFERRED"))
                .andExpect(jsonPath("$.proposedBy").doesNotExist())
                .andExpect(jsonPath("$.decidedBy").doesNotExist())
                .andExpect(content().string(not(containsString("owner-1"))))
                .andExpect(content().string(not(containsString("/workspace"))))
                .andExpect(content().string(not(containsString("token"))));

        verify(service).find(
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                WorkbenchId.of(WORKBENCH_ID), "operation-1");
    }

    @Test
    void listShouldUseTheOwnerScopedSafeQuery() throws Exception {
        when(service.list(any(), any())).thenReturn(
                Collections.singletonList(authorizedProjection()));

        mvc.perform(get(LIST_ROUTE, WORKBENCH_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operationId").value("operation-1"))
                .andExpect(jsonPath("$[0].executionAvailable").value(false));

        verify(service).list(
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                WorkbenchId.of(WORKBENCH_ID));
    }

    @Test
    void decisionShouldRequireVersionAndUseActualLoggedInActor() throws Exception {
        when(service.decide(any(), any(), eq("operation-1"),
                anyLong(), any(), any())).thenReturn(authorizedProjection());

        mvc.perform(post(DECISION_ROUTE, WORKBENCH_ID, "operation-1")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\","
                                + "\"reason\":\"已核对目标仓库和状态\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.executionAvailable").value(false))
                .andExpect(jsonPath("$.executionMode")
                        .value("MANUAL_OR_DEFERRED"));

        ArgumentCaptor<OwnerReference> actor =
                ArgumentCaptor.forClass(OwnerReference.class);
        verify(service).decide(
                actor.capture(), eq(WorkbenchId.of(WORKBENCH_ID)),
                eq("operation-1"), eq(0L),
                eq(HighImpactOperationDecision.APPROVE),
                eq("已核对目标仓库和状态"));
        assertEquals(OwnerReference.of(OWNER_ID, OWNER_NAME), actor.getValue());
    }

    @Test
    void invalidVersionDecisionAndUnknownFieldsShouldFailClosed() throws Exception {
        String valid = "{\"decision\":\"REJECT\",\"reason\":\"暂不执行\"}";
        mvc.perform(post(DECISION_ROUTE, WORKBENCH_ID, "operation-1")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_OPERATION_REQUEST_INVALID"));
        mvc.perform(post(DECISION_ROUTE, WORKBENCH_ID, "operation-1")
                        .header("If-Match", "-1")
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest());
        mvc.perform(post(DECISION_ROUTE, WORKBENCH_ID, "operation-1")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ALLOW\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(DECISION_ROUTE, WORKBENCH_ID, "operation-1")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reason\":\"x\","
                                + "\"actor\":\"admin\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).decide(
                any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void ownerHidingAndVersionConflictShouldUseStableSafeContracts()
            throws Exception {
        when(service.find(any(), any(), eq("missing")))
                .thenThrow(new OperationApplicationException(
                        OperationApplicationErrorCode.OPERATION_NOT_FOUND,
                        "high-impact operation was not found"));
        mvc.perform(get(DETAIL_ROUTE, WORKBENCH_ID, "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_OPERATION_NOT_FOUND"));

        when(service.decide(any(), any(), eq("operation-1"),
                anyLong(), any(), any()))
                .thenThrow(new OperationApplicationException(
                        OperationApplicationErrorCode.VERSION_CONFLICT,
                        "workbench high-impact operation version conflict",
                        authorizedProjection()));
        mvc.perform(post(DECISION_ROUTE, WORKBENCH_ID, "operation-1")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_OPERATION_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.current.version").value(3));
    }

    private HighImpactOperationProjection authorizedProjection() {
        Instant proposedAt = Instant.parse("2026-08-01T04:00:00Z");
        CommitTarget target = CommitTarget.create(
                "agent-web", "master", repeat('a', 40), repeat('b', 64),
                Arrays.asList(
                        DocumentReference.of("agent-web", "README.md"),
                        DocumentReference.of("agent-web", "src/main/App.java")),
                repeat('c', 64), "feat: workbench");
        HighImpactOperation operation = HighImpactOperation.restore(
                "operation-1", WorkbenchId.of(WORKBENCH_ID),
                WorkbenchRunReference.of(
                        "run-1", WorkbenchId.of(WORKBENCH_ID),
                        WorkbenchPhase.IMPLEMENT_TEST, "safe run summary"),
                target, target.requestedPayloadHash(), "Commit selected files",
                HighImpactOperationStatus.AUTHORIZED,
                OwnerReference.of(OWNER_ID, OWNER_NAME), proposedAt,
                OwnerReference.of(OWNER_ID, OWNER_NAME), "已核对目标",
                proposedAt.plusSeconds(10), proposedAt.plusSeconds(910),
                null, null, null, proposedAt.plusSeconds(10), 3L);
        return HighImpactOperationProjection.from(operation);
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
