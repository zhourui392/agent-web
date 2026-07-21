package com.example.agentweb.app.refinery;

import java.util.Collections;
import java.util.List;

/**
 * Public chat recall outcome: carries the final text sent to the CLI and the
 * public hit summary used by SSE recall cards.
 *
 * <p>{@link #isRecalled()} is true only when this turn has a displayable hit
 * card. In that case {@link #getMessage()} is the augmented prompt with history
 * context. Skipped, no-hit, and error-degraded attempts pass the original
 * message through with empty hits; full observability state lives in
 * {@link RecallTrace}.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-31
 */
public final class RecallOutcome {

    private final boolean recalled;
    private final String message;
    private final String query;
    private final List<RecallHit> hits;

    private RecallOutcome(boolean recalled, String message, String query, List<RecallHit> hits) {
        this.recalled = recalled;
        this.message = message;
        this.query = query;
        this.hits = hits;
    }

    /** No public recall card: pass the original message through without display. */
    public static RecallOutcome notRecalled(String original) {
        return new RecallOutcome(false, original, null, Collections.emptyList());
    }

    /** Hit recall: stores the public summary that can be shown to users. */
    public static RecallOutcome recalled(String augmentedMessage, String query, List<RecallHit> hits) {
        return new RecallOutcome(true, augmentedMessage, query,
                hits == null ? Collections.emptyList() : hits);
    }

    public boolean isRecalled() {
        return recalled;
    }

    public String getMessage() {
        return message;
    }

    public String getQuery() {
        return query;
    }

    public List<RecallHit> getHits() {
        return hits;
    }
}
