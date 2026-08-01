package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Workbench Phase 单次 Run 的规范化提交命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class SubmitWorkbenchRunCommand {

    private static final String HASH_SCHEMA = "workbench-run-submit-request@1";

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final long expectedVersion;
    private final String idempotencyKey;
    private final String message;
    private final RunMode runMode;
    private final Long handoffSourceVersion;
    private final String reviewConfirmationId;
    private final List<WorkbenchRunAttachmentReference> attachments;
    private final String requestHash;

    public SubmitWorkbenchRunCommand(
            WorkbenchId workbenchId, WorkbenchPhase phase, long expectedVersion,
            String idempotencyKey, String message, RunMode runMode,
            Long handoffSourceVersion, String reviewConfirmationId,
            List<WorkbenchRunAttachmentReference> attachments) {
        if (workbenchId == null || phase == null || runMode == null) {
            throw new IllegalArgumentException(
                    "workbench run identity, phase and mode are required");
        }
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "expected workbench version must not be negative");
        }
        if (handoffSourceVersion != null
                && handoffSourceVersion.longValue() < 0L) {
            throw new IllegalArgumentException(
                    "handoff source version must not be negative");
        }
        this.workbenchId = workbenchId;
        this.phase = phase;
        this.expectedVersion = expectedVersion;
        this.idempotencyKey = DomainText.require(
                idempotencyKey, "workbench run idempotency key", 128);
        this.message = DomainText.require(
                message, "workbench run message", 32000);
        this.runMode = runMode;
        this.handoffSourceVersion = handoffSourceVersion;
        this.reviewConfirmationId = normalizeOptional(
                reviewConfirmationId, "review confirmation id", 128);
        this.attachments = immutableAttachments(attachments);
        this.requestHash = computeRequestHash();
    }

    public static SubmitWorkbenchRunCommand fromExternal(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            long expectedVersion, String idempotencyKey,
            String message, String runMode,
            Long handoffSourceVersion, String reviewConfirmationId,
            List<WorkbenchRunAttachmentReference> attachments) {
        if (runMode == null) {
            throw new IllegalArgumentException(
                    "workbench run mode is required");
        }
        return new SubmitWorkbenchRunCommand(
                workbenchId, phase, expectedVersion, idempotencyKey,
                message, RunMode.valueOf(
                runMode.trim().toUpperCase(Locale.ROOT)),
                handoffSourceVersion, reviewConfirmationId, attachments);
    }

    private String computeRequestHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        CanonicalHashing.appendFramed(
                canonical, "workbenchId", workbenchId.getValue());
        CanonicalHashing.appendFramed(canonical, "phase", phase.name());
        CanonicalHashing.appendFramed(canonical, "message", message);
        CanonicalHashing.appendFramed(canonical, "runMode", runMode.name());
        CanonicalHashing.appendFramed(
                canonical, "handoffSourceVersion", handoffSourceVersion);
        CanonicalHashing.appendFramed(
                canonical, "reviewConfirmationId", reviewConfirmationId);
        CanonicalHashing.appendFramed(
                canonical, "attachmentCount", attachments.size());
        for (WorkbenchRunAttachmentReference attachment : attachments) {
            DocumentReference document = attachment.getDocumentReference();
            CanonicalHashing.appendFramed(
                    canonical, "attachmentRepositoryKey",
                    document.getRepositoryKey());
            CanonicalHashing.appendFramed(
                    canonical, "attachmentRelativePath",
                    document.getRelativePath());
            CanonicalHashing.appendFramed(
                    canonical, "attachmentContentHash",
                    attachment.getContentHash());
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private static List<WorkbenchRunAttachmentReference> immutableAttachments(
            List<WorkbenchRunAttachmentReference> values) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(
                    "workbench run attachments must not be null or contain null");
        }
        return Collections.unmodifiableList(
                new ArrayList<WorkbenchRunAttachmentReference>(values));
    }

    private static String normalizeOptional(
            String value, String name, int maximumLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return DomainText.require(value, name, maximumLength);
    }
}
