package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Recall attempt detail with hit snapshots.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
@Setter
public class RecallAttemptDetail extends RecallAttemptListItem {

    private String query;
    private String paramsJson;
    private String errorMessage;
    private List<RecallHitDetail> hits = new ArrayList<>();
}
