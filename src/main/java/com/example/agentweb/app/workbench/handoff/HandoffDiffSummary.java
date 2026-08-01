package com.example.agentweb.app.workbench.handoff;

import lombok.Getter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Accepted revision 与 latest Handoff 的安全字段级差异摘要。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HandoffDiffSummary {

    private final boolean summaryChanged;
    private final HandoffCollectionDiff decisions;
    private final HandoffCollectionDiff openQuestions;
    private final HandoffCollectionDiff pinnedFiles;
    private final HandoffCollectionDiff referencedRuns;

    public HandoffDiffSummary(
            boolean summaryChanged, HandoffCollectionDiff decisions,
            HandoffCollectionDiff openQuestions,
            HandoffCollectionDiff pinnedFiles,
            HandoffCollectionDiff referencedRuns) {
        if (decisions == null || openQuestions == null
                || pinnedFiles == null || referencedRuns == null) {
            throw new IllegalArgumentException(
                    "handoff diff fields are required");
        }
        this.summaryChanged = summaryChanged;
        this.decisions = decisions;
        this.openQuestions = openQuestions;
        this.pinnedFiles = pinnedFiles;
        this.referencedRuns = referencedRuns;
    }

    public static HandoffDiffSummary between(
            PhaseHandoffProjection accepted,
            PhaseHandoffProjection latest) {
        if (accepted == null || latest == null) {
            throw new IllegalArgumentException(
                    "accepted and latest handoffs are required");
        }
        return new HandoffDiffSummary(
                !accepted.getSummary().equals(latest.getSummary()),
                difference(accepted.getDecisions(), latest.getDecisions()),
                difference(
                        accepted.getOpenQuestions(),
                        latest.getOpenQuestions()),
                difference(
                        accepted.getPinnedFiles(), latest.getPinnedFiles()),
                differenceByRunId(
                        accepted.getReferencedRuns(),
                        latest.getReferencedRuns()));
    }

    private static HandoffCollectionDiff difference(
            List<?> accepted, List<?> latest) {
        Set<Object> acceptedSet = new HashSet<Object>(accepted);
        Set<Object> latestSet = new HashSet<Object>(latest);
        Set<Object> added = new HashSet<Object>(latestSet);
        added.removeAll(acceptedSet);
        Set<Object> removed = new HashSet<Object>(acceptedSet);
        removed.removeAll(latestSet);
        return new HandoffCollectionDiff(added.size(), removed.size());
    }

    private static HandoffCollectionDiff differenceByRunId(
            List<PhaseHandoffProjection.RunReferenceView> accepted,
            List<PhaseHandoffProjection.RunReferenceView> latest) {
        Set<String> acceptedIds = runIds(accepted);
        Set<String> latestIds = runIds(latest);
        Set<String> added = new HashSet<String>(latestIds);
        added.removeAll(acceptedIds);
        Set<String> removed = new HashSet<String>(acceptedIds);
        removed.removeAll(latestIds);
        return new HandoffCollectionDiff(added.size(), removed.size());
    }

    private static Set<String> runIds(
            List<PhaseHandoffProjection.RunReferenceView> references) {
        Set<String> ids = new HashSet<String>();
        for (PhaseHandoffProjection.RunReferenceView reference : references) {
            ids.add(reference.getRunId());
        }
        return ids;
    }
}
