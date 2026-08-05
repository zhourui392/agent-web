package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Dynamic Stage 已终态或过期上传附件的幂等清理编排。
 *
 * @author alex
 * @since 2026-08-05
 */
@Service
public class WorkbenchStageUploadedAttachmentCleanupService {

    private final WorkbenchStageUploadedConversationAttachmentRepository
            repository;
    private final UploadedConversationAttachmentStorage storage;
    private final UploadedAttachmentCleanupSettings settings;
    private final Clock clock;

    public WorkbenchStageUploadedAttachmentCleanupService(
            WorkbenchStageUploadedConversationAttachmentRepository repository,
            UploadedConversationAttachmentStorage storage,
            UploadedAttachmentCleanupSettings settings, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelayString =
            "${agent.workbench.attachment.cleanup-delay-millis:60000}")
    @Transactional
    public void cleanup() {
        Instant now = clock.instant();
        List<WorkbenchStageUploadedConversationAttachment> candidates =
                repository.findCleanupCandidates(
                        now, settings.getBatchSize());
        for (WorkbenchStageUploadedConversationAttachment candidate
                : candidates) {
            candidate.requireCleanupAt(now);
            storage.delete(candidate.getStorageKey());
            repository.delete(candidate);
        }
    }
}
