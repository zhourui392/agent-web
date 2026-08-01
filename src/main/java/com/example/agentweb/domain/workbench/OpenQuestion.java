package com.example.agentweb.domain.workbench;

import lombok.Getter;

import java.util.Objects;

/**
 * 人工记录的未决问题，不自动推导 resolved 状态。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class OpenQuestion {

    private final String text;
    private final String ownerHint;

    private OpenQuestion(String text, String ownerHint) {
        this.text = WorkbenchText.requireUntrustedText(
                text, "open question text", 2000);
        this.ownerHint = WorkbenchText.optionalUntrustedText(
                ownerHint, "open question owner hint", 256);
    }

    public static OpenQuestion of(String text, String ownerHint) {
        return new OpenQuestion(text, ownerHint);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OpenQuestion)) {
            return false;
        }
        OpenQuestion that = (OpenQuestion) other;
        return text.equals(that.text) && Objects.equals(ownerHint, that.ownerHint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, ownerHint);
    }
}
