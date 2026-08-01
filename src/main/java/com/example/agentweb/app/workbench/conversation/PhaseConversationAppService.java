package com.example.agentweb.app.workbench.conversation;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseConversationProvisioning;
import com.example.agentweb.domain.workbench.PhaseConversationRestartReceipt;
import com.example.agentweb.domain.workbench.PhaseConversationRestartReceiptRepository;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase Conversation 的创建、可信核验与幂等重启事务编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
@Transactional(readOnly = true)
public class PhaseConversationAppService {

    private final WorkbenchRepository workbenchRepository;
    private final SessionRepository sessionRepository;
    private final PhaseConversationRestartReceiptRepository receiptRepository;
    private final PhaseSessionIdGenerator sessionIdGenerator;
    private final Clock clock;

    public PhaseConversationAppService(
            WorkbenchRepository workbenchRepository,
            SessionRepository sessionRepository,
            PhaseConversationRestartReceiptRepository receiptRepository,
            PhaseSessionIdGenerator sessionIdGenerator,
            Clock clock) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository, "sessionRepository");
        this.receiptRepository = Objects.requireNonNull(
                receiptRepository, "receiptRepository");
        this.sessionIdGenerator = Objects.requireNonNull(
                sessionIdGenerator, "sessionIdGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public PhaseConversationResult ensureConversation(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase, long expectedVersion) {
        requireBoundary(actor, workbenchId, phase);
        Workbench workbench = requireWorkbench(workbenchId);
        PhaseConversationProvisioning provisioning = obscureOwner(() ->
                workbench.planConversationEnsure(phase, actor, expectedVersion));
        if (provisioning.hasCurrentConversation()) {
            requireTrustedSession(provisioning);
            return PhaseConversationResult.existing(provisioning);
        }
        Instant now = persistedNow();
        ChatSession session = createSession(provisioning, sessionIdGenerator.nextId(), now);
        PhaseConversationProvisioning changed = workbench.bindConversationAndDescribe(
                phase, session.getId(), actor, now);
        sessionRepository.addSession(session);
        workbenchRepository.update(workbench);
        return PhaseConversationResult.changed(changed, null);
    }

    @Transactional
    public PhaseConversationResult restartConversation(
            OwnerReference actor, RestartPhaseConversationCommand command) {
        if (actor == null || command == null) {
            throw new IllegalArgumentException("restart actor and command are required");
        }
        Optional<PhaseConversationRestartReceipt> existing =
                receiptRepository.findByOwnerAndIdempotencyKey(
                        actor, command.getIdempotencyKey());
        if (existing.isPresent()) {
            PhaseConversationRestartReceipt replayed = existing.get().requireReplay(
                    actor, command.getIdempotencyKey(),
                    command.getWorkbenchId(), command.getPhase());
            return PhaseConversationResult.replayed(replayed);
        }

        Workbench workbench = requireWorkbench(command.getWorkbenchId());
        PhaseConversationProvisioning provisioning = obscureOwner(() ->
                workbench.planConversationRestart(
                        command.getPhase(), actor, command.getExpectedVersion()));
        ChatSession previousSession = requireTrustedSession(provisioning);
        Instant now = persistedNow();
        ChatSession newSession = createSession(
                provisioning, sessionIdGenerator.nextId(), now);

        previousSession.retire(now);
        PhaseConversationProvisioning changed = workbench.restartConversationAndDescribe(
                command.getPhase(), newSession.getId(), actor, now);
        PhaseConversationResult result = PhaseConversationResult.changed(
                changed, previousSession.getId());
        PhaseConversationRestartReceipt receipt = PhaseConversationRestartReceipt.record(
                actor, command.getIdempotencyKey(), command.getWorkbenchId(),
                command.getPhase(), result.getPreviousSessionId(), result.getSessionId(),
                result.getConversationGeneration(), result.getWorkbenchVersion(), now);

        sessionRepository.saveSession(previousSession);
        sessionRepository.addSession(newSession);
        workbenchRepository.update(workbench);
        receiptRepository.add(receipt);
        return result;
    }

    private ChatSession requireTrustedSession(PhaseConversationProvisioning provisioning) {
        ChatSession session = sessionRepository.findById(
                provisioning.getCurrentConversationId());
        if (session == null) {
            throw new IllegalStateException("Phase conversation session is unavailable");
        }
        session.requireActiveWorkbenchPhase(
                provisioning.getCurrentConversationId(), provisioning.getAgentType(),
                provisioning.getPrimaryRepositoryRoot(), provisioning.getEnvironment(),
                provisioning.getContextId(), provisioning.getOwner().getOwnerId(),
                provisioning.getOwner().getOwnerName(),
                provisioning.getCurrentConversationCreatedAt());
        return session;
    }

    private ChatSession createSession(
            PhaseConversationProvisioning provisioning,
            String sessionId, Instant now) {
        ChatSession session = ChatSession.createWorkbenchPhase(
                sessionId, provisioning.getAgentType(),
                provisioning.getPrimaryRepositoryRoot(), provisioning.getContextId(),
                provisioning.getOwner().getOwnerId(),
                provisioning.getOwner().getOwnerName(), now);
        session.setEnv(provisioning.getEnvironment());
        return session;
    }

    private Instant persistedNow() {
        return Instant.ofEpochMilli(clock.instant().toEpochMilli());
    }

    private Workbench requireWorkbench(WorkbenchId workbenchId) {
        return workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
    }

    private <T> T obscureOwner(DomainAction<T> action) {
        try {
            return action.execute();
        } catch (WorkbenchDomainException ex) {
            if (ex.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
                throw new WorkbenchNotFoundException();
            }
            throw ex;
        }
    }

    private void requireBoundary(
            OwnerReference actor, WorkbenchId workbenchId, WorkbenchPhase phase) {
        if (actor == null || workbenchId == null || phase == null) {
            throw new IllegalArgumentException(
                    "phase conversation actor, workbench and phase are required");
        }
    }

    @FunctionalInterface
    private interface DomainAction<T> {
        T execute();
    }
}
