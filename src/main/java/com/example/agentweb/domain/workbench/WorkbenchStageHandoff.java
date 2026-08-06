package com.example.agentweb.domain.workbench;

import lombok.Getter;

/**
 * 某个 Workbench Stage 的 handoff 摘要，用于 prompt 注入全阶段 handoff 路径。
 *
 * @author alex
 * @since 2026-08-06
 */
@Getter
public final class WorkbenchStageHandoff {

    private final String definitionIdentifier;
    private final int sequenceNumber;
    private final String displayName;

    public WorkbenchStageHandoff(
            String definitionIdentifier, int sequenceNumber,
            String displayName) {
        if (definitionIdentifier == null
                || definitionIdentifier.isBlank()) {
            throw new IllegalArgumentException(
                    "Workbench Stage Handoff definition identifier is required");
        }
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException(
                    "Workbench Stage Handoff sequence number must be positive");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException(
                    "Workbench Stage Handoff display name is required");
        }
        this.definitionIdentifier = definitionIdentifier;
        this.sequenceNumber = sequenceNumber;
        this.displayName = displayName;
    }
}
