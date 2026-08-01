package com.example.agentweb.app.workbench.conversation;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

/**
 * Phase Conversation restart 的可信应用命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RestartPhaseConversationCommand {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final String idempotencyKey;
    private final long expectedVersion;

    public RestartPhaseConversationCommand(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            String idempotencyKey, long expectedVersion) {
        if (workbenchId == null || phase == null) {
            throw new IllegalArgumentException("restart workbench and phase are required");
        }
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expected workbench version must not be negative");
        }
        this.workbenchId = workbenchId;
        this.phase = phase;
        this.idempotencyKey = DomainText.require(
                idempotencyKey, "phase conversation restart idempotency key", 128);
        this.expectedVersion = expectedVersion;
    }
}
