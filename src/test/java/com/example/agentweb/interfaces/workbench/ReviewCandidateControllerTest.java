package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.review.ReviewApplicationErrorCode;
import com.example.agentweb.app.workbench.review.ReviewApplicationException;
import com.example.agentweb.app.workbench.review.ReviewCandidateAppService;
import com.example.agentweb.app.workbench.review.ReviewCandidateView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.interfaces.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review Candidate 只读生成、安全投影与稳定错误合同测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@WebMvcTest(ReviewCandidateController.class)
@Import({GlobalExceptionHandler.class, WorkbenchExceptionHandler.class})
class ReviewCandidateControllerTest {

    private static final String OWNER_ID = "owner-1";
    private static final String OWNER_NAME = "Alex";
    private static final String WORKBENCH_ID = "workbench-1";
    private static final String ROUTE =
            "/api/workbenches/{id}/phases/REVIEW_REFACTOR/review-candidates";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ReviewCandidateAppService service;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void authenticateOwner() {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(currentUserProvider.currentUserName()).thenReturn(OWNER_NAME);
    }

    @Test
    void postShouldReturnOnlySafeReadOnlyCandidateFields() throws Exception {
        ReviewCandidateView candidate = candidateView();
        when(service.generate(any(), any())).thenReturn(candidate);

        mvc.perform(post(ROUTE, WORKBENCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("REVIEW_REFACTOR"))
                .andExpect(jsonPath("$.baseOpinionVersion").value(2))
                .andExpect(jsonPath("$.conversationGeneration").value(1))
                .andExpect(jsonPath("$.sourceMessageCount").value(50))
                .andExpect(jsonPath("$.strategy").value(
                        "DETERMINISTIC_PUBLIC_REVIEW_MESSAGES_V1"))
                .andExpect(jsonPath("$.items[0].itemId").value("item-1"))
                .andExpect(jsonPath("$.items[0].finding").value("领域规则外泄"))
                .andExpect(jsonPath("$.items[0].impact").value("分支漂移"))
                .andExpect(jsonPath("$.items[0].suggestedChange")
                        .value("下沉聚合"))
                .andExpect(jsonPath(
                        "$.items[0].affectedFiles[0].repositoryKey")
                        .value("agent-web"))
                .andExpect(jsonPath(
                        "$.items[0].affectedFiles[0].relativePath")
                        .value("src/main/java/A.java"))
                .andExpect(jsonPath("$.items[0].suggestedTests[0]")
                        .value("运行领域单测"))
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.workbenchId").doesNotExist())
                .andExpect(jsonPath("$.conversationId").doesNotExist())
                .andExpect(jsonPath("$.accepted").doesNotExist())
                .andExpect(jsonPath("$.confirmation").doesNotExist())
                .andExpect(content().string(not(containsString(OWNER_ID))))
                .andExpect(content().string(not(containsString("/home/"))))
                .andExpect(content().string(not(containsString("toolOutput"))));

        verify(service).generate(
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                WorkbenchId.of(WORKBENCH_ID));
    }

    @Test
    void unknownCommandFieldOrMalformedWorkbenchIdShouldFailClosed()
            throws Exception {
        mvc.perform(post(ROUTE, WORKBENCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoSave\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_REVIEW_CANDIDATE_REQUEST_INVALID"));
        mvc.perform(post(ROUTE, " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_REVIEW_CANDIDATE_REQUEST_INVALID"));

        verifyNoInteractions(service);
    }

    @Test
    void unavailableMissingAndRestartRaceShouldUseStableStatuses()
            throws Exception {
        when(service.generate(any(), any()))
                .thenThrow(new ReviewApplicationException(
                        ReviewApplicationErrorCode.CANDIDATE_SOURCE_UNAVAILABLE,
                        "current review public conversation is unavailable"))
                .thenThrow(new WorkbenchNotFoundException())
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.CONVERSATION_CONFLICT,
                        "review candidate source conversation changed"));

        mvc.perform(post(ROUTE, WORKBENCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_REVIEW_CANDIDATE_SOURCE_UNAVAILABLE"));
        mvc.perform(post(ROUTE, WORKBENCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKBENCH_NOT_FOUND"));
        mvc.perform(post(ROUTE, WORKBENCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_CONVERSATION_CONFLICT"));
    }

    private static ReviewCandidateView candidateView() {
        ReviewCandidateView view = mock(ReviewCandidateView.class);
        ReviewCandidateView.ItemView item =
                mock(ReviewCandidateView.ItemView.class);
        ReviewCandidateView.DocumentReferenceView file =
                mock(ReviewCandidateView.DocumentReferenceView.class);
        when(view.getPhase()).thenReturn(WorkbenchPhase.REVIEW_REFACTOR);
        when(view.getBaseOpinionVersion()).thenReturn(2L);
        when(view.getConversationGeneration()).thenReturn(1);
        when(view.getSourceMessageCount()).thenReturn(50);
        when(view.getStrategy()).thenReturn(
                "DETERMINISTIC_PUBLIC_REVIEW_MESSAGES_V1");
        when(view.getItems()).thenReturn(Collections.singletonList(item));
        when(item.getItemId()).thenReturn("item-1");
        when(item.getFinding()).thenReturn("领域规则外泄");
        when(item.getImpact()).thenReturn("分支漂移");
        when(item.getSuggestedChange()).thenReturn("下沉聚合");
        when(item.getAffectedFiles()).thenReturn(
                Collections.singletonList(file));
        when(item.getSuggestedTests()).thenReturn(
                Collections.singletonList("运行领域单测"));
        when(file.getRepositoryKey()).thenReturn("agent-web");
        when(file.getRelativePath()).thenReturn("src/main/java/A.java");
        return view;
    }
}
