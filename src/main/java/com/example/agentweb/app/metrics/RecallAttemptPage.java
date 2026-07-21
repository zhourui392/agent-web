package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Paged recall attempts.
 *
 * @author codex
 * @since 2026-06-12
 */
@Getter
@Setter
public class RecallAttemptPage {

    private int page;
    private int size;
    private long total;
    private List<RecallAttemptListItem> items = new ArrayList<>();
}
