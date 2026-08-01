package com.example.agentweb.app.workbench.run;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Workbench Run 历史的有界游标页。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunListPage {

    private final List<WorkbenchRunListItemView> items;
    private final WorkbenchRunListCursor nextCursor;

    public WorkbenchRunListPage(
            List<WorkbenchRunListItemView> items,
            WorkbenchRunListCursor nextCursor) {
        this.items = Collections.unmodifiableList(
                new ArrayList<WorkbenchRunListItemView>(
                        Objects.requireNonNull(items, "items")));
        this.nextCursor = nextCursor;
    }
}
