package com.example.agentweb.app.workbench.review;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.query.PhaseConversationMessagePage;
import com.example.agentweb.app.workbench.query.PhaseConversationMessageRequest;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.ReviewCandidate;
import com.example.agentweb.domain.workbench.ReviewCandidateConversation;
import com.example.agentweb.domain.workbench.ReviewCandidateGenerator;
import com.example.agentweb.domain.workbench.ReviewCandidateMessage;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.ReviewOpinionRepository;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 当前 Review 公开会话到非持久化 Candidate 的只读应用编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
@Transactional(readOnly = true)
public class ReviewCandidateAppService {

    private final WorkbenchRepository workbenchRepository;
    private final ReviewOpinionRepository opinionRepository;
    private final WorkbenchQueryService queryService;
    private final ReviewCandidateGenerator generator;

    public ReviewCandidateAppService(
            WorkbenchRepository workbenchRepository,
            ReviewOpinionRepository opinionRepository,
            WorkbenchQueryService queryService) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.opinionRepository = Objects.requireNonNull(
                opinionRepository, "opinionRepository");
        this.queryService = Objects.requireNonNull(
                queryService, "queryService");
        this.generator = new ReviewCandidateGenerator();
    }

    public ReviewCandidateView generate(
            OwnerReference actor, WorkbenchId workbenchId) {
        Workbench workbench = requireOperableWorkbench(actor, workbenchId);
        PhaseConversationMessagePage page = queryService
                .findCurrentPhaseConversationByOwner(
                        actor.getOwnerId(), workbenchId.getValue(),
                        WorkbenchPhase.REVIEW_REFACTOR,
                        PhaseConversationMessageRequest.latest())
                .orElseThrow(ReviewCandidateAppService::sourceUnavailable);
        if (page.getSessionId() == null) {
            throw sourceUnavailable();
        }
        Optional<ReviewOpinion> baseOpinion =
                opinionRepository.findLatest(workbenchId);
        ReviewCandidate candidate = generator.generate(
                actor, workbench, baseOpinion, conversation(page));
        return ReviewCandidateView.from(candidate);
    }

    private ReviewCandidateConversation conversation(
            PhaseConversationMessagePage page) {
        List<ReviewCandidateMessage> messages =
                new ArrayList<ReviewCandidateMessage>(
                        page.getMessages().size());
        for (PhaseConversationMessagePage.MessageView message
                : page.getMessages()) {
            messages.add(ReviewCandidateMessage.publicMessage(
                    message.getMessageId(), message.getRole(),
                    message.getContent()));
        }
        return ReviewCandidateConversation.capture(
                page.getSessionId(), page.getGeneration(), messages);
    }

    private Workbench requireOperableWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workbenchId, "workbenchId");
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
        try {
            workbench.requireOperableBy(actor);
            return workbench;
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() != WorkbenchErrorCode.OWNER_REQUIRED) {
                throw failure;
            }
            throw new WorkbenchNotFoundException();
        }
    }

    private static ReviewApplicationException sourceUnavailable() {
        return new ReviewApplicationException(
                ReviewApplicationErrorCode.CANDIDATE_SOURCE_UNAVAILABLE,
                "current review public conversation is unavailable");
    }
}
