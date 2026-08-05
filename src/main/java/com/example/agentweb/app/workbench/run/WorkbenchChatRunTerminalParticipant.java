package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.ChatRunTerminalParticipant;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshotRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageUploadedConversationAttachment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Workbench Run 首次终态的 Snapshot 定位与聚合持久化编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class WorkbenchChatRunTerminalParticipant
        implements ChatRunTerminalParticipant {

    private final WorkbenchStageRunSnapshotRepository snapshotRepository;
    private final WorkbenchRepository workbenchRepository;
    private final WorkbenchStageUploadedConversationAttachmentRepository
            attachmentRepository;
    private final ChatRunRepository runRepository;
    private final ChatRunEventAppender eventAppender;
    private final WorkbenchTelemetry telemetry;

    public WorkbenchChatRunTerminalParticipant(
            WorkbenchStageRunSnapshotRepository snapshotRepository,
            WorkbenchRepository workbenchRepository,
            WorkbenchStageUploadedConversationAttachmentRepository
                    attachmentRepository,
            ChatRunRepository runRepository,
            ChatRunEventAppender eventAppender,
            WorkbenchTelemetry telemetry) {
        this.snapshotRepository = snapshotRepository;
        this.workbenchRepository = workbenchRepository;
        this.attachmentRepository = attachmentRepository;
        this.runRepository = runRepository;
        this.eventAppender = eventAppender;
        this.telemetry = telemetry;
    }

    @Override
    public RunOrigin origin() {
        return RunOrigin.WORKBENCH;
    }

    @Override
    public void onFirstTerminal(ChatRunId runId, Instant terminalAt) {
        if (runId == null || terminalAt == null) {
            throw new IllegalArgumentException(
                    "chat run id and terminal time must not be null");
        }
        WorkbenchStageRunSnapshot snapshot = snapshotRepository
                .findByRunId(runId.getValue())
                .orElseThrow(WorkbenchDomainException::runBindingCorrupted);
        Workbench workbench = workbenchRepository.findById(snapshot.getWorkbenchId())
                .orElseThrow(WorkbenchDomainException::runBindingCorrupted);
        snapshot.finishRequiredRun(workbench, runId.getValue(), terminalAt);
        releaseUploadedAttachments(snapshot, runId.getValue(), terminalAt);
        workbenchRepository.update(workbench);
        eventAppender.afterCommit(new Runnable() {
            @Override
            public void run() {
                recordPersistedTerminal(runId, snapshot, workbench);
            }
        });
    }

    private void releaseUploadedAttachments(
            WorkbenchStageRunSnapshot snapshot,
            String runId, Instant terminalAt) {
        for (VerifiedWorkbenchStageUploadedConversationAttachment verified
                : snapshot.getVerifiedUploadedAttachments()) {
            WorkbenchStageUploadedConversationAttachment attachment =
                    attachmentRepository
                            .findById(verified.getAttachmentId())
                            .orElseThrow(
                                    WorkbenchDomainException::runBindingCorrupted);
            long expectedVersion = attachment.getVersion();
            attachment.releaseAfterTerminal(runId, terminalAt);
            attachmentRepository.update(attachment, expectedVersion);
        }
    }

    private void recordPersistedTerminal(
            ChatRunId runId, WorkbenchStageRunSnapshot snapshot,
            Workbench workbench) {
        ChatRun persisted = runRepository.findById(runId)
                .orElseThrow(WorkbenchDomainException::runBindingCorrupted);
        snapshot.requireExactRun(workbench, persisted, runId.getValue());
        persisted.requireTerminal();
        Duration duration = Duration.between(
                persisted.getCreatedAt(), persisted.getFinishedAt());
        telemetry.runTerminal(
                snapshot.getRunMode(), persisted.getStatus().name(), duration);
    }
}
