package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.RunMode;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * Dynamic Stage 内的活动 Run 引用，只携带精确 Stage 实例与 Run 身份。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageRunReference {

    private final String runIdentifier;
    private final RunMode runMode;
    private final Instant preparedAt;

    private WorkbenchStageRunReference(
            String runIdentifier, RunMode runMode, Instant preparedAt) {
        this.runIdentifier = DomainText.require(
                runIdentifier, "Stage Run identifier", 128);
        if (runMode == null) {
            throw new IllegalArgumentException("Stage Run Mode is required");
        }
        this.runMode = runMode;
        this.preparedAt = DomainText.requireTime(
                preparedAt, "Stage Run preparation time");
    }

    public static WorkbenchStageRunReference prepare(
            String runIdentifier, RunMode runMode, Instant preparedAt) {
        return new WorkbenchStageRunReference(
                runIdentifier, runMode, preparedAt);
    }

    public static WorkbenchStageRunReference restore(
            String runIdentifier, RunMode runMode, Instant preparedAt) {
        return new WorkbenchStageRunReference(
                runIdentifier, runMode, preparedAt);
    }

    public boolean matches(String candidateRunIdentifier) {
        return runIdentifier.equals(DomainText.require(
                candidateRunIdentifier, "Stage Run identifier", 128));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkbenchStageRunReference)) {
            return false;
        }
        WorkbenchStageRunReference that =
                (WorkbenchStageRunReference) other;
        return runIdentifier.equals(that.runIdentifier)
                && runMode == that.runMode
                && preparedAt.equals(that.preparedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runIdentifier, runMode, preparedAt);
    }
}
