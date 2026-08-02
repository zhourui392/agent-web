package com.example.agentweb.app.workbench.admin;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Admin Workbench Run 有界列表页。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AdminWorkbenchRunListPage {

    private final List<AdminWorkbenchRunListItemView> items;
    private final AdminWorkbenchRunListCursor nextCursor;

    public AdminWorkbenchRunListPage(
            List<AdminWorkbenchRunListItemView> items,
            AdminWorkbenchRunListCursor nextCursor) {
        if (items == null || items.contains(null)) {
            throw new IllegalArgumentException(
                    "admin workbench run list items are required");
        }
        this.items = Collections.unmodifiableList(
                new ArrayList<AdminWorkbenchRunListItemView>(items));
        this.nextCursor = nextCursor;
    }
}
