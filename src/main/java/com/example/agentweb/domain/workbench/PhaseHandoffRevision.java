package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.workspace.RepositoryScope;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase Handoff 某个 exact version 的不可变领域修订。
 *
 * <p>Revision 只从已通过聚合不变量校验的 Handoff capture/restore，后续 latest
 * 聚合继续修订不会污染已固化的历史事实。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseHandoffRevision {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase sourcePhase;
    private final String summary;
    private final List<Decision> decisions;
    private final List<OpenQuestion> openQuestions;
    private final List<DocumentReference> pinnedFiles;
    private final List<WorkbenchRunReference> referencedRuns;
    private final String contentHash;
    private final OwnerReference updatedBy;
    private final Instant updatedAt;
    private final long version;

    private PhaseHandoffRevision(PhaseHandoff handoff) {
        this.workbenchId = handoff.getWorkbenchId();
        this.sourcePhase = handoff.getSourcePhase();
        this.summary = handoff.getSummary();
        this.decisions = immutableCopy(handoff.getDecisions());
        this.openQuestions = immutableCopy(handoff.getOpenQuestions());
        this.pinnedFiles = immutableCopy(handoff.getPinnedFiles());
        this.referencedRuns = immutableCopy(handoff.getReferencedRuns());
        this.contentHash = handoff.getContentHash();
        this.updatedBy = handoff.getUpdatedBy();
        this.updatedAt = handoff.getUpdatedAt();
        this.version = handoff.getVersion();
    }

    public static PhaseHandoffRevision capture(PhaseHandoff handoff) {
        if (handoff == null) {
            throw new IllegalArgumentException("phase handoff must not be null");
        }
        return new PhaseHandoffRevision(handoff);
    }

    public static PhaseHandoffRevision restore(
            WorkbenchId workbenchId, WorkbenchPhase sourcePhase,
            String summary, List<Decision> decisions,
            List<OpenQuestion> openQuestions, List<DocumentReference> pinnedFiles,
            List<WorkbenchRunReference> referencedRuns, String contentHash,
            OwnerReference updatedBy, Instant updatedAt, long version,
            RepositoryScope repositoryScope) {
        PhaseHandoff validated = PhaseHandoff.restore(
                workbenchId, sourcePhase, summary, decisions, openQuestions,
                pinnedFiles, referencedRuns, contentHash, updatedBy, updatedAt,
                version, repositoryScope);
        return capture(validated);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
