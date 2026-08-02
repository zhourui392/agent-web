package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;

/**
 * Workbench 管理员运维动作的追加式审计事实。
 *
 * <p>只记录逻辑身份、动作和稳定结果，不保存路径、命令、Tool 输出或异常正文。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchAdminAuditEntry {

    private final WorkbenchAdministrator administrator;
    private final WorkbenchId workbenchId;
    private final String runId;
    private final WorkbenchAdminAction action;
    private final String outcome;
    private final Instant occurredAt;

    private WorkbenchAdminAuditEntry(
            WorkbenchAdministrator administrator,
            WorkbenchId workbenchId, String runId,
            WorkbenchAdminAction action, String outcome,
            Instant occurredAt) {
        if (administrator == null || workbenchId == null
                || action == null || occurredAt == null) {
            throw new IllegalArgumentException(
                    "workbench administrator audit facts are required");
        }
        this.administrator = administrator;
        this.workbenchId = workbenchId;
        this.runId = DomainText.require(
                runId, "workbench administrator audit run id", 128);
        this.action = action;
        this.outcome = requireOutcome(outcome);
        this.occurredAt = occurredAt;
    }

    public static WorkbenchAdminAuditEntry record(
            WorkbenchAdministrator administrator,
            WorkbenchId workbenchId, String runId,
            WorkbenchAdminAction action, String outcome,
            Instant occurredAt) {
        return new WorkbenchAdminAuditEntry(
                administrator, workbenchId, runId,
                action, outcome, occurredAt);
    }

    private static String requireOutcome(String value) {
        String normalized = DomainText.require(
                value, "workbench administrator audit outcome", 64);
        if (!normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "workbench administrator audit outcome is invalid");
        }
        return normalized;
    }
}
