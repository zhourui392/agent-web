package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Objects;

/**
 * 消费者限定的工作区快照采集目的；公共 Workspace 不声明 Workbench 枚举。

 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class SnapshotPurpose {

    private static final String PURPOSE_PATTERN = "[A-Z][A-Z0-9_]{0,63}";

    private final String value;

    private SnapshotPurpose(String value) {
        this.value = value;
    }

    public static SnapshotPurpose of(String value) {
        String normalized = DomainText.require(value, "workspace snapshot purpose", 64);
        if (!normalized.matches(PURPOSE_PATTERN)) {
            throw new IllegalArgumentException(
                    "workspace snapshot purpose must be an uppercase stable identifier");
        }
        return new SnapshotPurpose(normalized);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapshotPurpose)) {
            return false;
        }
        SnapshotPurpose that = (SnapshotPurpose) other;
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
