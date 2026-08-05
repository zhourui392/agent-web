package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.attachment.port.StoredUploadedAttachment;
import com.example.agentweb.app.workbench.attachment.port.UploadedAttachmentStorageRequest;
import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentQueryService;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
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
 * Dynamic Stage multipart 上传到领域附件和受控存储的应用编排。
 *
 * @author alex
 * @since 2026-08-05
 */
@Service
public class WorkbenchStageUploadedConversationAttachmentAppService {

    private final WorkbenchRepository workbenchRepository;
    private final WorkbenchStageUploadedConversationAttachmentRepository
            attachmentRepository;
    private final WorkbenchStageUploadedConversationAttachmentQueryService
            attachmentQueryService;
    private final UploadedConversationAttachmentStorage storage;
    private final UploadedAttachmentIdGenerator identifierGenerator;
    private final UploadedAttachmentPolicy policy;
    private final Clock clock;

    public WorkbenchStageUploadedConversationAttachmentAppService(
            WorkbenchRepository workbenchRepository,
            WorkbenchStageUploadedConversationAttachmentRepository
                    attachmentRepository,
            WorkbenchStageUploadedConversationAttachmentQueryService
                    attachmentQueryService,
            UploadedConversationAttachmentStorage storage,
            UploadedAttachmentIdGenerator identifierGenerator,
            UploadedAttachmentPolicy policy, Clock clock) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.attachmentRepository = Objects.requireNonNull(
                attachmentRepository, "attachmentRepository");
        this.attachmentQueryService = Objects.requireNonNull(
                attachmentQueryService, "attachmentQueryService");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.identifierGenerator = Objects.requireNonNull(
                identifierGenerator, "identifierGenerator");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public WorkbenchStageUploadedConversationAttachmentView upload(
            OwnerReference actor,
            UploadWorkbenchStageConversationAttachmentCommand command,
            InputStream inputStream) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(inputStream, "inputStream");
        Workbench workbench = workbenchRepository
                .findById(command.getWorkbenchId())
                .orElseThrow(WorkbenchNotFoundException::new);
        WorkbenchStageUploadedAttachmentBinding binding = obscureOwner(() ->
                workbench.planStageUploadedAttachment(
                        command.getStageInstanceIdentifier(),
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
            WorkbenchStageUploadedConversationAttachment attachment =
                    WorkbenchStageUploadedConversationAttachment.upload(
                            identifierGenerator.nextId(), binding,
                            command.getDisplayName(),
                            command.getClientMediaType(),
                            stored.getContentSignature(), stored.getSize(),
                            stored.getSha256(), stored.getStorageKey(),
                            policy, now);
            attachmentRepository.add(attachment);
            return WorkbenchStageUploadedConversationAttachmentView.from(
                    attachment);
        } catch (RuntimeException failure) {
            rollback.discard();
            throw failure;
        }
    }

    @Transactional
    public void cancel(
            OwnerReference actor, WorkbenchId workbenchId,
            String stageInstanceIdentifier, int conversationGeneration,
            String attachmentId) {
        Objects.requireNonNull(actor, "actor");
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
        WorkbenchStageUploadedAttachmentBinding binding = obscureOwner(() ->
                workbench.planStageUploadedAttachment(
                        stageInstanceIdentifier,
                        conversationGeneration, actor));
        WorkbenchStageUploadedConversationAttachment attachment =
                attachmentRepository.findById(attachmentId)
                        .orElseThrow(() -> new WorkbenchDomainException(
                                WorkbenchErrorCode.ATTACHMENT_UNAVAILABLE,
                                "Dynamic Stage uploaded attachment is unavailable"));
        long expectedVersion = attachment.getVersion();
        attachment.cancelAvailable(binding, clock.instant());
        attachmentRepository.update(attachment, expectedVersion);
    }

    private void discard(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException ignoredFailure) {
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
                WorkbenchStageUploadedConversationAttachmentAppService.this
                        .discard(storageKey);
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
