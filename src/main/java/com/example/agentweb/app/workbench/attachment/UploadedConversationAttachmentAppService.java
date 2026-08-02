package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.attachment.port.StoredUploadedAttachment;
import com.example.agentweb.app.workbench.attachment.port.UploadedAttachmentStorageRequest;
import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentQueryService;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * multipart 上传到领域附件和受控存储的应用编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class UploadedConversationAttachmentAppService {

    private final WorkbenchRepository workbenchRepository;
    private final UploadedConversationAttachmentRepository attachmentRepository;
    private final UploadedConversationAttachmentQueryService attachmentQueryService;
    private final UploadedConversationAttachmentStorage storage;
    private final UploadedAttachmentIdGenerator idGenerator;
    private final UploadedAttachmentPolicy policy;
    private final Clock clock;

    public UploadedConversationAttachmentAppService(
            WorkbenchRepository workbenchRepository,
            UploadedConversationAttachmentRepository attachmentRepository,
            UploadedConversationAttachmentQueryService attachmentQueryService,
            UploadedConversationAttachmentStorage storage,
            UploadedAttachmentIdGenerator idGenerator,
            UploadedAttachmentPolicy policy, Clock clock) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.attachmentRepository = Objects.requireNonNull(
                attachmentRepository, "attachmentRepository");
        this.attachmentQueryService = Objects.requireNonNull(
                attachmentQueryService, "attachmentQueryService");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public UploadedConversationAttachmentView upload(
            OwnerReference actor, UploadConversationAttachmentCommand command,
            InputStream inputStream) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(inputStream, "inputStream");
        Workbench workbench = workbenchRepository
                .findById(command.getWorkbenchId())
                .orElseThrow(WorkbenchNotFoundException::new);
        UploadedAttachmentBinding binding = obscureOwner(() ->
                workbench.planUploadedAttachment(
                        command.getPhase(),
                        command.getConversationGeneration(), actor));
        Instant now = clock.instant();
        policy.requireAvailableQuota(
                attachmentQueryService.countAvailable(binding, now));
        StoredUploadedAttachment stored = storage.store(
                new UploadedAttachmentStorageRequest(
                        inputStream, command.getDeclaredSize()));
        StoredObjectRollback rollback = new StoredObjectRollback(
                stored.getStorageKey());
        rollback.register();
        try {
            UploadedConversationAttachment attachment =
                    UploadedConversationAttachment.upload(
                            idGenerator.nextId(), binding,
                            command.getDisplayName(),
                            command.getClientMediaType(),
                            stored.getContentSignature(), stored.getSize(),
                            stored.getSha256(), stored.getStorageKey(),
                            policy, now);
            attachmentRepository.add(attachment);
            return UploadedConversationAttachmentView.from(attachment);
        } catch (RuntimeException failure) {
            rollback.discard();
            throw failure;
        }
    }

    @Transactional
    public void cancel(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase,
            int conversationGeneration, String attachmentId) {
        Objects.requireNonNull(actor, "actor");
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
        UploadedAttachmentBinding binding = obscureOwner(() ->
                workbench.planUploadedAttachment(
                        phase, conversationGeneration, actor));
        UploadedConversationAttachment attachment = attachmentRepository
                .findById(attachmentId)
                .orElseThrow(() -> new WorkbenchDomainException(
                        WorkbenchErrorCode.ATTACHMENT_UNAVAILABLE,
                        "uploaded attachment is unavailable"));
        long expectedVersion = attachment.getVersion();
        attachment.cancelAvailable(binding, clock.instant());
        attachmentRepository.update(attachment, expectedVersion);
    }

    private void discard(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException ignored) {
            // 不能用物理路径或次生清理异常覆盖原始业务拒绝。
        }
    }

    private final class StoredObjectRollback {

        private final String storageKey;
        private final AtomicBoolean discarded = new AtomicBoolean();

        private StoredObjectRollback(String storageKey) {
            this.storageKey = storageKey;
        }

        private void register() {
            if (!TransactionSynchronizationManager
                    .isSynchronizationActive()) {
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status
                                    != TransactionSynchronization.STATUS_COMMITTED) {
                                discard();
                            }
                        }
                    });
        }

        private void discard() {
            if (discarded.compareAndSet(false, true)) {
                UploadedConversationAttachmentAppService.this.discard(
                        storageKey);
            }
        }
    }

    private <T> T obscureOwner(DomainAction<T> action) {
        try {
            return action.execute();
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
                throw new WorkbenchNotFoundException();
            }
            throw failure;
        }
    }

    @FunctionalInterface
    private interface DomainAction<T> {
        T execute();
    }
}
