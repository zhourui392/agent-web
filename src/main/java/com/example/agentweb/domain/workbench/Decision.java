package com.example.agentweb.domain.workbench;

import lombok.Getter;

import java.util.Objects;

/**
 * 人工确认的阶段决定；MVP 不引入审批状态机。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class Decision {

    public enum Status {
        CONFIRMED
    }

    private final String text;
    private final String rationale;
    private final Status status;

    private Decision(String text, String rationale) {
        this.text = WorkbenchText.requireUntrustedText(text, "decision text", 2000);
        this.rationale = WorkbenchText.optionalUntrustedText(
                rationale, "decision rationale", 2000);
        this.status = Status.CONFIRMED;
    }

    public static Decision confirmed(String text, String rationale) {
        return new Decision(text, rationale);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Decision)) {
            return false;
        }
        Decision that = (Decision) other;
        return text.equals(that.text)
                && Objects.equals(rationale, that.rationale)
                && status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, rationale, status);
    }
}
