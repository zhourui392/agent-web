package com.example.agentweb.app.workbench.conversation;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationProvisioning;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationRestartReceipt;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationRestartReceiptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 动态 Stage Conversation 的创建、可信核验与幂等重启编排。
 *
 * @author alex
 * @since 2026-08-05
 */
@Service
@Transactional(readOnly = true)
public class WorkbenchStageConversationAppService {

    private final WorkbenchRepository workbenchRepository;
    private final SessionRepository sessionRepository;
    private final WorkbenchStageConversationRestartReceiptRepository
            receiptRepository;
    private final WorkbenchStageSessionIdGenerator sessionIdGenerator;
    private final Clock clock;

    public WorkbenchStageConversationAppService(
            WorkbenchRepository workbenchRepository,
            SessionRepository sessionRepository,
            WorkbenchStageConversationRestartReceiptRepository receiptRepository,
            WorkbenchStageSessionIdGenerator sessionIdGenerator,
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
    public WorkbenchStageConversationResult ensureConversation(
            OwnerReference actor, WorkbenchId workbenchId,
            String stageInstanceIdentifier, long expectedVersion) {
        requireBoundary(actor, workbenchId, stageInstanceIdentifier);
        Workbench workbench = requireWorkbench(workbenchId);
        WorkbenchStageConversationProvisioning provisioning = obscureOwner(
                () -> workbench.planStageConversationEnsure(
                        stageInstanceIdentifier, actor, expectedVersion));
        if (provisioning.hasCurrentConversation()) {
            requireTrustedSession(provisioning);
            return WorkbenchStageConversationResult.existing(provisioning);
        }
        Instant now = persistedNow();
        ChatSession session = createSession(
                provisioning, sessionIdGenerator.nextId(), now);
        WorkbenchStageConversationProvisioning changed =
                workbench.bindStageConversationAndDescribe(
                        stageInstanceIdentifier, session.getId(), actor, now);
        sessionRepository.addSession(session);
        workbenchRepository.update(workbench);
        return WorkbenchStageConversationResult.changed(changed, null);
    }

    @Transactional
    public WorkbenchStageConversationResult restartConversation(
            OwnerReference actor,
            RestartWorkbenchStageConversationCommand command) {
        if (actor == null || command == null) {
            throw new IllegalArgumentException(
                    "Stage restart actor and command are required");
        }
        Optional<WorkbenchStageConversationRestartReceipt> existing =
                receiptRepository.findByOwnerAndIdempotencyKey(
                        actor, command.getIdempotencyKey());
        if (existing.isPresent()) {
            WorkbenchStageConversationRestartReceipt replayed =
                    existing.get().requireReplay(
                            actor, command.getIdempotencyKey(),
                            command.getWorkbenchId(),
                            command.getStageInstanceIdentifier());
            return WorkbenchStageConversationResult.replayed(replayed);
        }

        Workbench workbench = requireWorkbench(command.getWorkbenchId());
        WorkbenchStageConversationProvisioning provisioning = obscureOwner(
                () -> workbench.planStageConversationRestart(
                        command.getStageInstanceIdentifier(), actor,
                        command.getExpectedVersion()));
        ChatSession previousSession = requireTrustedSession(provisioning);
        Instant now = persistedNow();
        ChatSession newSession = createSession(
                provisioning, sessionIdGenerator.nextId(), now);

        previousSession.retire(now);
        WorkbenchStageConversationProvisioning changed =
                workbench.restartStageConversationAndDescribe(
                        command.getStageInstanceIdentifier(),
                        newSession.getId(), actor, now);
        WorkbenchStageConversationResult result =
                WorkbenchStageConversationResult.changed(
                        changed, previousSession.getId());
        WorkbenchStageConversationRestartReceipt receipt =
                WorkbenchStageConversationRestartReceipt.record(
                        actor, command.getIdempotencyKey(),
                        command.getWorkbenchId(),
                        command.getStageInstanceIdentifier(),
                        result.getPreviousSessionId(), result.getSessionId(),
                        result.getConversationGeneration(),
                        result.getWorkbenchVersion(), now);

        sessionRepository.saveSession(previousSession);
        sessionRepository.addSession(newSession);
        workbenchRepository.update(workbench);
        receiptRepository.add(receipt);
        return result;
    }

    private ChatSession requireTrustedSession(
            WorkbenchStageConversationProvisioning provisioning) {
        ChatSession session = sessionRepository.findById(
                provisioning.getCurrentConversationId());
        if (session == null) {
            throw new IllegalStateException(
                    "Stage conversation Session is unavailable");
        }
        session.requireActiveWorkbenchStage(
                provisioning.getCurrentConversationId(),
                provisioning.getAgentType(),
                provisioning.getPrimaryRepositoryRoot(),
                provisioning.getEnvironment(), provisioning.getContextId(),
                provisioning.getOwner().getOwnerId(),
                provisioning.getOwner().getOwnerName(),
                provisioning.getCurrentConversationCreatedAt());
        return session;
    }

    private ChatSession createSession(
            WorkbenchStageConversationProvisioning provisioning,
            String sessionId, Instant now) {
        ChatSession session = ChatSession.createWorkbenchStage(
                sessionId, provisioning.getAgentType(),
                provisioning.getPrimaryRepositoryRoot(),
                provisioning.getContextId(),
                provisioning.getOwner().getOwnerId(),
                provisioning.getOwner().getOwnerName(), now);
        session.setEnv(provisioning.getEnvironment());
        return session;
    }

    private Workbench requireWorkbench(WorkbenchId workbenchId) {
        return workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
    }

    private Instant persistedNow() {
        return Instant.ofEpochMilli(clock.instant().toEpochMilli());
    }

    private void requireBoundary(
            OwnerReference actor, WorkbenchId workbenchId,
            String stageInstanceIdentifier) {
        if (actor == null || workbenchId == null
                || stageInstanceIdentifier == null
                || stageInstanceIdentifier.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Stage conversation boundary is required");
        }
    }

    private <T> T obscureOwner(DomainAction<T> action) {
        try {
            return action.execute();
        } catch (WorkbenchDomainException exception) {
            if (exception.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
                throw new WorkbenchNotFoundException();
            }
            throw exception;
        }
    }

    @FunctionalInterface
    private interface DomainAction<T> {

        T execute();
    }
}
