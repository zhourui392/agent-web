package com.example.agentweb.app.workbench.admin;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Admin Workbench 有界列表页。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AdminWorkbenchListPage {

    private final List<AdminWorkbenchListItemView> items;
    private final AdminWorkbenchListCursor nextCursor;

    public AdminWorkbenchListPage(
            List<AdminWorkbenchListItemView> items,
            AdminWorkbenchListCursor nextCursor) {
        if (items == null || items.contains(null)) {
            throw new IllegalArgumentException(
                    "admin workbench list items are required");
        }
        this.items = Collections.unmodifiableList(
                new ArrayList<AdminWorkbenchListItemView>(items));
        this.nextCursor = nextCursor;
    }
}
