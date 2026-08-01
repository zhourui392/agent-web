package com.example.agentweb.app.workbench.query;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Workbench Owner 列表游标分页。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchListPage {

    private final List<WorkbenchListItemView> items;
    private final WorkbenchListCursor nextCursor;

    public WorkbenchListPage(
            List<WorkbenchListItemView> items,
            WorkbenchListCursor nextCursor) {
        this.items = Collections.unmodifiableList(new ArrayList<WorkbenchListItemView>(
                Objects.requireNonNull(items, "items")));
        this.nextCursor = nextCursor;
    }
}
