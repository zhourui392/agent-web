package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageRunAttachmentSet;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import lombok.Getter;

import java.util.Objects;

/**
 * Dynamic Stage Run 外部副作用前的完整不可变候选。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class PreparedWorkbenchStageRun {

    private final SubmitWorkbenchStageRunCommand command;
    private final WorkbenchStageRunSnapshot snapshot;
    private final WorkspaceSnapshot workspaceSnapshot;
    private final WorkbenchRunPromptPayload promptPayload;
    private final VerifiedWorkbenchStageRunAttachmentSet verifiedAttachments;

    private PreparedWorkbenchStageRun(
            SubmitWorkbenchStageRunCommand command,
            WorkbenchStageRunSnapshot snapshot,
            WorkspaceSnapshot workspaceSnapshot,
            WorkbenchRunPromptPayload promptPayload,
            VerifiedWorkbenchStageRunAttachmentSet verifiedAttachments) {
        this.command = Objects.requireNonNull(command, "command");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.workspaceSnapshot = Objects.requireNonNull(
                workspaceSnapshot, "workspaceSnapshot");
        this.promptPayload = Objects.requireNonNull(
                promptPayload, "promptPayload");
        this.verifiedAttachments = Objects.requireNonNull(
                verifiedAttachments, "verifiedAttachments");
        snapshot.requireReplay(
                command.getWorkbenchId(),
                command.getStageInstanceIdentifier(),
                command.getIdempotencyKey(), command.getRequestHash());
        snapshot.requireWorkspaceSnapshot(workspaceSnapshot);
        snapshot.requirePromptPayload(promptPayload);
        if (!snapshot.getVerifiedAttachments().equals(
                verifiedAttachments.getRepositoryDocuments())
                || !snapshot.getVerifiedUploadedAttachments().equals(
                verifiedAttachments.getUploadedAttachments())) {
            throw new IllegalArgumentException(
                    "prepared Dynamic Stage attachments do not match Snapshot");
        }
    }

    public static PreparedWorkbenchStageRun of(
            SubmitWorkbenchStageRunCommand command,
            WorkbenchStageRunSnapshot snapshot,
            WorkspaceSnapshot workspaceSnapshot,
            WorkbenchRunPromptPayload promptPayload,
            VerifiedWorkbenchStageRunAttachmentSet verifiedAttachments) {
        return new PreparedWorkbenchStageRun(
                command, snapshot, workspaceSnapshot,
                promptPayload, verifiedAttachments);
    }
}
