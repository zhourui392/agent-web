package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 进入提交事务前已完成 Snapshot、Prompt、Handoff 与 Review 解析的 Run 候选。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PreparedWorkbenchRun {

    private final SubmitWorkbenchRunCommand command;
    private final WorkbenchRunSnapshot snapshot;
    private final WorkspaceSnapshot workspaceSnapshot;
    private final WorkbenchRunPromptPayload promptPayload;
    private final ReviewModifyConfirmation reviewConfirmation;
    private final HandoffReception handoffReception;
    private final List<VerifiedWorkbenchRunAttachment> verifiedAttachments;

    private PreparedWorkbenchRun(
            SubmitWorkbenchRunCommand command,
            WorkbenchRunSnapshot snapshot,
            WorkspaceSnapshot workspaceSnapshot,
            WorkbenchRunPromptPayload promptPayload,
            ReviewModifyConfirmation reviewConfirmation,
            HandoffReception handoffReception,
            List<VerifiedWorkbenchRunAttachment> verifiedAttachments) {
        if (command == null || snapshot == null || workspaceSnapshot == null
                || promptPayload == null) {
            throw new IllegalArgumentException(
                    "prepared workbench run required values must not be null");
        }
        snapshot.requireReplay(
                command.getWorkbenchId(), command.getPhase(),
                command.getIdempotencyKey(), command.getRequestHash());
        snapshot.requireWorkspaceSnapshot(workspaceSnapshot);
        snapshot.requirePromptPayload(promptPayload);
        snapshot.requireReviewConfirmation(reviewConfirmation);
        snapshot.requireHandoffReception(handoffReception);
        this.command = command;
        this.snapshot = snapshot;
        this.workspaceSnapshot = workspaceSnapshot;
        this.promptPayload = promptPayload;
        this.reviewConfirmation = reviewConfirmation;
        this.handoffReception = handoffReception;
        this.verifiedAttachments =
                VerifiedWorkbenchRunAttachment.immutableList(
                        verifiedAttachments);
    }

    public static PreparedWorkbenchRun of(
            SubmitWorkbenchRunCommand command,
            WorkbenchRunSnapshot snapshot,
            WorkspaceSnapshot workspaceSnapshot,
            WorkbenchRunPromptPayload promptPayload,
            ReviewModifyConfirmation reviewConfirmation,
            HandoffReception handoffReception) {
        return new PreparedWorkbenchRun(
                command, snapshot, workspaceSnapshot, promptPayload,
                reviewConfirmation, handoffReception,
                Collections.<VerifiedWorkbenchRunAttachment>emptyList());
    }

    public static PreparedWorkbenchRun of(
            SubmitWorkbenchRunCommand command,
            WorkbenchRunSnapshot snapshot,
            WorkspaceSnapshot workspaceSnapshot,
            WorkbenchRunPromptPayload promptPayload,
            ReviewModifyConfirmation reviewConfirmation,
            HandoffReception handoffReception,
            List<VerifiedWorkbenchRunAttachment> verifiedAttachments) {
        return new PreparedWorkbenchRun(
                command, snapshot, workspaceSnapshot, promptPayload,
                reviewConfirmation, handoffReception, verifiedAttachments);
    }
}
