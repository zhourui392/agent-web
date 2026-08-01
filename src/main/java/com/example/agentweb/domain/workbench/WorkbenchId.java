package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Objects;

/**
 * Workbench 聚合标识。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchId {

    private final String value;

    private WorkbenchId(String value) {
        this.value = DomainText.require(value, "workbench id", 128);
    }

    public static WorkbenchId of(String value) {
        return new WorkbenchId(value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkbenchId)) {
            return false;
        }
        WorkbenchId that = (WorkbenchId) other;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
