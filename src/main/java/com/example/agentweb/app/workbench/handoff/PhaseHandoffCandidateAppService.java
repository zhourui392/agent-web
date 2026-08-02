package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.app.workbench.query.PhaseConversationMessagePage;
import com.example.agentweb.app.workbench.query.PhaseConversationMessageRequest;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.domain.workbench.HandoffCandidateConversation;
import com.example.agentweb.domain.workbench.HandoffCandidateMessage;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PhaseHandoffCandidate;
import com.example.agentweb.domain.workbench.PhaseHandoffCandidateGenerator;
import com.example.agentweb.domain.workbench.PhaseHandoffRepository;
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
 * 当前 Phase 公开消息到非持久化 Handoff Candidate 的只读应用编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
@Transactional(readOnly = true)
public class PhaseHandoffCandidateAppService {

    private final WorkbenchRepository workbenchRepository;
    private final PhaseHandoffRepository handoffRepository;
    private final WorkbenchQueryService queryService;
    private final PhaseHandoffCandidateGenerator generator;

    public PhaseHandoffCandidateAppService(
            WorkbenchRepository workbenchRepository,
            PhaseHandoffRepository handoffRepository,
            WorkbenchQueryService queryService) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.handoffRepository = Objects.requireNonNull(
                handoffRepository, "handoffRepository");
        this.queryService = Objects.requireNonNull(
                queryService, "queryService");
        this.generator = new PhaseHandoffCandidateGenerator();
    }

    public PhaseHandoffCandidateProjection generate(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase sourcePhase) {
        Workbench workbench = requireOperableWorkbench(actor, workbenchId);
        PhaseConversationMessagePage page = queryService
                .findCurrentPhaseConversationByOwner(
                        actor.getOwnerId(), workbenchId.getValue(), sourcePhase,
                        PhaseConversationMessageRequest.latest())
                .orElseThrow(PhaseHandoffCandidateAppService::sourceUnavailable);
        if (page.getSessionId() == null) {
            throw sourceUnavailable();
        }
        Optional<PhaseHandoff> base = handoffRepository.find(
                workbenchId, sourcePhase);
        PhaseHandoffCandidate candidate = generator.generate(
                actor, workbench, sourcePhase, base, conversation(page));
        return PhaseHandoffCandidateProjection.from(candidate);
    }

    private HandoffCandidateConversation conversation(
            PhaseConversationMessagePage page) {
        List<HandoffCandidateMessage> messages =
                new ArrayList<HandoffCandidateMessage>(
                        page.getMessages().size());
        for (PhaseConversationMessagePage.MessageView message
                : page.getMessages()) {
            messages.add(HandoffCandidateMessage.publicMessage(
                    message.getMessageId(), message.getRole(),
                    message.getContent(), message.getRunId()));
        }
        return HandoffCandidateConversation.capture(
                page.getSessionId(), page.getGeneration(), messages);
    }

    private Workbench requireOperableWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(workbenchId, "workbenchId");
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(PhaseHandoffCandidateAppService::workbenchNotFound);
        try {
            workbench.requireOperableBy(actor);
            return workbench;
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() != WorkbenchErrorCode.OWNER_REQUIRED) {
                throw failure;
            }
            throw workbenchNotFound();
        }
    }

    private static HandoffApplicationException workbenchNotFound() {
        return new HandoffApplicationException(
                HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                "workbench was not found");
    }

    private static HandoffApplicationException sourceUnavailable() {
        return new HandoffApplicationException(
                HandoffApplicationErrorCode.CANDIDATE_SOURCE_UNAVAILABLE,
                "current phase public conversation is unavailable");
    }
}
