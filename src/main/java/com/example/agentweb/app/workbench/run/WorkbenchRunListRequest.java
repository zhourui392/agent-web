package com.example.agentweb.app.workbench.run;

import lombok.Getter;

import java.util.regex.Pattern;

/**
 * Workbench Run 历史列表查询条件。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunListRequest {

    public static final int MAX_LIMIT = 100;
    private static final Pattern STAGE_IDENTIFIER_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private final String stageInstanceIdentifier;
    private final WorkbenchRunListCursor cursor;
    private final int limit;

    public WorkbenchRunListRequest(
            String stageInstanceIdentifier, WorkbenchRunListCursor cursor,
            int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "run history limit must be between 1 and " + MAX_LIMIT);
        }
        this.stageInstanceIdentifier = normalizeStageIdentifier(
                stageInstanceIdentifier);
        this.cursor = cursor;
        this.limit = limit;
    }

    private static String normalizeStageIdentifier(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (!STAGE_IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Stage instance identifier is invalid");
        }
        return normalized;
    }
}
