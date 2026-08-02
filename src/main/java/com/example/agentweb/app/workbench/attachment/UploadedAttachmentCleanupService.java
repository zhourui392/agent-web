package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 已终态或过期上传附件的幂等受控存储清理编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class UploadedAttachmentCleanupService {

    private final UploadedConversationAttachmentRepository repository;
    private final UploadedConversationAttachmentStorage storage;
    private final UploadedAttachmentCleanupSettings settings;
    private final Clock clock;

    public UploadedAttachmentCleanupService(
            UploadedConversationAttachmentRepository repository,
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
        List<UploadedConversationAttachment> candidates =
                repository.findCleanupCandidates(
                        now, settings.getBatchSize());
        for (UploadedConversationAttachment candidate : candidates) {
            candidate.requireCleanupAt(now);
            storage.delete(candidate.getStorageKey());
            repository.delete(candidate);
        }
    }
}
