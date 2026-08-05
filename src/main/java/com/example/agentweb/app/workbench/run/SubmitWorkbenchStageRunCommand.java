package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference;
import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentSelection;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCommandInvocation;
import lombok.Getter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 动态 Workbench Stage 单次 Run 的规范化提交命令。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class SubmitWorkbenchStageRunCommand {

    private static final String HASH_SCHEMA =
            "workbench-stage-run-submit-request@1";
    private static final Pattern STAGE_IDENTIFIER_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private final WorkbenchId workbenchId;
    private final String stageInstanceIdentifier;
    private final long expectedVersion;
    private final String idempotencyKey;
    private final String message;
    private final RunMode runMode;
    private final WorkbenchStageCommandInvocation commandInvocation;
    private final List<WorkbenchRunAttachmentReference> attachments;
    private final String requestHash;

    public SubmitWorkbenchStageRunCommand(
            WorkbenchId workbenchId, String stageInstanceIdentifier,
            long expectedVersion, String idempotencyKey,
            String message, RunMode runMode,
            List<WorkbenchRunAttachmentReference> attachments) {
        if (workbenchId == null || runMode == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Run identity and mode are required");
        }
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "Expected Workbench version must not be negative");
        }
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = requireStageIdentifier(
                stageInstanceIdentifier);
        this.expectedVersion = expectedVersion;
        this.idempotencyKey = DomainText.require(
                idempotencyKey, "Workbench Stage Run idempotency key", 128);
        this.message = DomainText.require(
                message, "Workbench Stage Run message", 32000);
        this.runMode = runMode;
        this.commandInvocation = WorkbenchStageCommandInvocation.parse(
                this.message);
        this.attachments = WorkbenchRunAttachmentSelection.immutable(
                attachments);
        this.requestHash = calculateRequestHash();
    }

    public static SubmitWorkbenchStageRunCommand fromExternal(
            WorkbenchId workbenchId, String stageInstanceIdentifier,
            long expectedVersion, String idempotencyKey,
            String message, String runMode,
            List<WorkbenchRunAttachmentReference> attachments) {
        if (runMode == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Run mode is required");
        }
        return new SubmitWorkbenchStageRunCommand(
                workbenchId, stageInstanceIdentifier, expectedVersion,
                idempotencyKey, message,
                RunMode.valueOf(runMode.trim().toUpperCase(Locale.ROOT)),
                attachments);
    }

    private String calculateRequestHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        CanonicalHashing.appendFramed(
                canonical, "workbenchId", workbenchId.getValue());
        CanonicalHashing.appendFramed(
                canonical, "stageInstanceIdentifier",
                stageInstanceIdentifier);
        CanonicalHashing.appendFramed(canonical, "message", message);
        CanonicalHashing.appendFramed(
                canonical, "runMode", runMode.name());
        CanonicalHashing.appendFramed(
                canonical, "commandIdentifier",
                commandInvocation == null
                        ? null : commandInvocation.getIdentifier());
        CanonicalHashing.appendFramed(
                canonical, "commandArguments",
                commandInvocation == null
                        ? null : commandInvocation.getArguments());
        CanonicalHashing.appendFramed(
                canonical, "attachmentCount", attachments.size());
        for (WorkbenchRunAttachmentReference attachment : attachments) {
            attachment.appendCanonical(canonical);
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private static String requireStageIdentifier(String value) {
        String normalized = DomainText.require(
                value, "Stage Instance identifier", 128);
        if (!STAGE_IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Stage Instance identifier is invalid");
        }
        return normalized;
    }
}
