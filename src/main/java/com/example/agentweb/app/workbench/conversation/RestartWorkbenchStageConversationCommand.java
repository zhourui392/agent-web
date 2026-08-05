package com.example.agentweb.app.workbench.conversation;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.WorkbenchId;
import lombok.Getter;

/**
 * 动态 Stage Conversation restart 的可信应用命令。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class RestartWorkbenchStageConversationCommand {

    private final WorkbenchId workbenchId;
    private final String stageInstanceIdentifier;
    private final String idempotencyKey;
    private final long expectedVersion;

    public RestartWorkbenchStageConversationCommand(
            WorkbenchId workbenchId, String stageInstanceIdentifier,
            String idempotencyKey, long expectedVersion) {
        if (workbenchId == null || expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "Stage restart Workbench and version are required");
        }
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = DomainText.require(
                stageInstanceIdentifier, "Stage Instance identifier", 128);
        this.idempotencyKey = DomainText.require(
                idempotencyKey,
                "Stage conversation restart idempotency key", 128);
        this.expectedVersion = expectedVersion;
    }
}
