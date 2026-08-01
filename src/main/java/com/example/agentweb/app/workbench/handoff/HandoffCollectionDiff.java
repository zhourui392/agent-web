package com.example.agentweb.app.workbench.handoff;

import lombok.Getter;

/**
 * Handoff 集合字段的新增与删除数量。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HandoffCollectionDiff {

    private final int added;
    private final int removed;

    public HandoffCollectionDiff(int added, int removed) {
        if (added < 0 || removed < 0) {
            throw new IllegalArgumentException(
                    "handoff diff counts must not be negative");
        }
        this.added = added;
        this.removed = removed;
    }
}
