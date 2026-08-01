package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.review.ConfirmReviewModificationCommand;
import com.example.agentweb.app.workbench.review.ReviewApplicationErrorCode;
import com.example.agentweb.app.workbench.review.ReviewApplicationException;
import com.example.agentweb.app.workbench.review.ReviewConfirmationView;
import com.example.agentweb.app.workbench.review.ReviewOpinionView;
import com.example.agentweb.app.workbench.review.ReviewOwnerService;
import com.example.agentweb.app.workbench.review.SaveReviewOpinionCommand;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review Opinion 与 exact Modify Confirmation 的 Owner HTTP 合同测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@WebMvcTest(ReviewOpinionController.class)
@Import({GlobalExceptionHandler.class, WorkbenchExceptionHandler.class})
class ReviewOpinionControllerTest {

    private static final String OWNER_ID = "owner-1";
    private static final String OWNER_NAME = "Alex";
    private static final String WORKBENCH_ID = "workbench-1";
    private static final String BASE_ROUTE =
            "/api/workbenches/{id}/phases/REVIEW_REFACTOR";
    private static final String OPINION_ROUTE =
            BASE_ROUTE + "/review-opinion";
    private static final String CONFIRMATION_ROUTE =
            BASE_ROUTE + "/review-confirmation";
    private static final Instant NOW =
            Instant.parse("2026-08-01T15:00:00Z");
    private static final String CONTENT_A = "提取 Review 策略对象";
    private static final String CONTENT_B = "拆分 Review 策略并运行测试";
    private static final String HASH_A = CanonicalHashing.sha256(CONTENT_A);
    private static final String HASH_B = CanonicalHashing.sha256(CONTENT_B);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ReviewOwnerService service;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void authenticateOwner() {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(currentUserProvider.currentUserName()).thenReturn(OWNER_NAME);
    }

    @Test
    void getOpinionShouldUseRealActorAndReturnOnlySafeHashProjection()
            throws Exception {
        when(service.getOpinion(any(), any()))
                .thenReturn(opinionView(2L, HASH_B, false));

        mvc.perform(get(OPINION_ROUTE, WORKBENCH_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("REVIEW_REFACTOR"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contentHash").value(HASH_B))
                .andExpect(jsonPath("$.content").value(CONTENT_B))
                .andExpect(jsonPath("$.reviewedAt")
                        .value(NOW.toEpochMilli()))
                .andExpect(jsonPath("$.readOnly").value(false))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(content().string(not(containsString("/home/"))));

        verify(service).getOpinion(
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                WorkbenchId.of(WORKBENCH_ID));
    }

    @Test
    void putOpinionShouldRequireIfMatchAndPassExactHashCommand()
            throws Exception {
        when(service.saveOpinion(any(), any()))
                .thenReturn(opinionView(3L, HASH_A, false));

        mvc.perform(put(OPINION_ROUTE, WORKBENCH_ID)
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + CONTENT_A + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.contentHash").value(HASH_A));

        ArgumentCaptor<SaveReviewOpinionCommand> command =
                ArgumentCaptor.forClass(SaveReviewOpinionCommand.class);
        verify(service).saveOpinion(
                org.mockito.ArgumentMatchers.eq(
                        OwnerReference.of(OWNER_ID, OWNER_NAME)),
                command.capture());
        assertEquals(WorkbenchId.of(WORKBENCH_ID),
                command.getValue().getWorkbenchId());
        assertEquals(2L, command.getValue().getExpectedVersion());
        assertEquals(CONTENT_A, command.getValue().getContent());
    }

    @Test
    void postConfirmationShouldUseOnlyExactOpinionProofAndReturnCreated()
            throws Exception {
        when(service.confirmModification(any(), any()))
                .thenReturn(confirmationView(false));

        mvc.perform(post(CONFIRMATION_ROUTE, WORKBENCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opinionVersion\":2,"
                                + "\"opinionHash\":\"" + HASH_B + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confirmationId")
                        .value("confirmation-2"))
                .andExpect(jsonPath("$.opinionVersion").value(2))
                .andExpect(jsonPath("$.opinionHash").value(HASH_B))
                .andExpect(jsonPath("$.confirmedBy").doesNotExist())
                .andExpect(jsonPath("$.opinion").doesNotExist());

        ArgumentCaptor<ConfirmReviewModificationCommand> command =
                ArgumentCaptor.forClass(
                        ConfirmReviewModificationCommand.class);
        verify(service).confirmModification(
                org.mockito.ArgumentMatchers.eq(
                        OwnerReference.of(OWNER_ID, OWNER_NAME)),
                command.capture());
        assertEquals(2L, command.getValue().getOpinionVersion());
        assertEquals(HASH_B, command.getValue().getOpinionHash());
    }

    @Test
    void getConfirmationShouldRecoverArchivedReadOnlyProjection()
            throws Exception {
        when(service.getConfirmation(any(), any()))
                .thenReturn(confirmationView(true));

        mvc.perform(get(CONFIRMATION_ROUTE, WORKBENCH_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationId")
                        .value("confirmation-2"))
                .andExpect(jsonPath("$.readOnly").value(true));
    }

    @Test
    void unknownOrTextConfirmationFieldsShouldFailClosedBeforeService()
            throws Exception {
        mvc.perform(post(CONFIRMATION_ROUTE, WORKBENCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opinionVersion\":2,"
                                + "\"opinionHash\":\"" + HASH_B + "\","
                                + "\"message\":\"请改一下\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_REVIEW_REQUEST_INVALID"));
        mvc.perform(put(OPINION_ROUTE, WORKBENCH_ID)
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + CONTENT_A + "\","
                                + "\"reviewedBy\":\"attacker\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_REVIEW_REQUEST_INVALID"));

        verifyNoInteractions(service);
    }

    @Test
    void malformedHashVersionOrMissingIfMatchShouldUseStable400()
            throws Exception {
        mvc.perform(put(OPINION_ROUTE, WORKBENCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_REVIEW_REQUEST_INVALID"));
        mvc.perform(put(OPINION_ROUTE, WORKBENCH_ID)
                        .header("If-Match", "-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + CONTENT_A + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(OPINION_ROUTE, WORKBENCH_ID)
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\""
                                + new String(new char[16001])
                                .replace('\0', 'x') + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_REVIEW_REQUEST_INVALID"));
        mvc.perform(post(CONFIRMATION_ROUTE, WORKBENCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opinionVersion\":0,"
                                + "\"opinionHash\":\"" + HASH_A + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void versionConflictShouldReturn409WithSafeCurrentOpinion()
            throws Exception {
        when(service.saveOpinion(any(), any())).thenThrow(
                new ReviewApplicationException(
                        ReviewApplicationErrorCode.VERSION_CONFLICT,
                        "review opinion version or hash changed",
                        opinionView(2L, HASH_B, false)));

        mvc.perform(put(OPINION_ROUTE, WORKBENCH_ID)
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + CONTENT_A + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_REVIEW_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.current.version").value(2))
                .andExpect(jsonPath("$.current.contentHash").value(HASH_B))
                .andExpect(jsonPath("$.current.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.current.content").value(CONTENT_B));
    }

    @Test
    void missingRecordsOwner404AndArchivedWriteShouldKeepStableStatuses()
            throws Exception {
        when(service.getOpinion(any(), any()))
                .thenThrow(new ReviewApplicationException(
                        ReviewApplicationErrorCode.OPINION_NOT_FOUND,
                        "review opinion was not found"))
                .thenThrow(new WorkbenchNotFoundException());
        when(service.getConfirmation(any(), any()))
                .thenThrow(new ReviewApplicationException(
                        ReviewApplicationErrorCode.CONFIRMATION_NOT_FOUND,
                        "review confirmation was not found"));
        when(service.saveOpinion(any(), any())).thenThrow(
                new WorkbenchDomainException(
                        WorkbenchErrorCode.ARCHIVED,
                        "archived workbench is read-only"));

        mvc.perform(get(OPINION_ROUTE, WORKBENCH_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_REVIEW_OPINION_NOT_FOUND"));
        mvc.perform(get(CONFIRMATION_ROUTE, WORKBENCH_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_REVIEW_CONFIRMATION_NOT_FOUND"));
        mvc.perform(get(OPINION_ROUTE, WORKBENCH_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_NOT_FOUND"));
        mvc.perform(put(OPINION_ROUTE, WORKBENCH_ID)
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + CONTENT_A + "\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_ARCHIVED"));
    }

    private static ReviewOpinionView opinionView(
            long version, String hash, boolean readOnly) {
        String content = HASH_A.equals(hash) ? CONTENT_A : CONTENT_B;
        return ReviewOpinionView.from(
                ReviewOpinion.restore(
                        WorkbenchId.of(WORKBENCH_ID), version,
                        content, hash,
                        OwnerReference.of(OWNER_ID, OWNER_NAME), NOW),
                readOnly);
    }

    private static ReviewConfirmationView confirmationView(
            boolean readOnly) {
        ReviewOpinion opinion = ReviewOpinion.record(
                WorkbenchId.of(WORKBENCH_ID), 2L, HASH_B,
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                NOW.minusSeconds(1));
        return ReviewConfirmationView.from(
                ReviewModifyConfirmation.confirm(
                        "confirmation-2", opinion,
                        OwnerReference.of(OWNER_ID, OWNER_NAME), NOW),
                readOnly);
    }
}
