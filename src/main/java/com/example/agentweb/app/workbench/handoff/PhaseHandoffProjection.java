package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.Decision;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.OpenQuestion;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PhaseHandoffRevision;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase Handoff 的 Owner-safe HTTP 投影。
 *
 * <p>有意不暴露 WorkbenchId、updatedBy 以及 Run 所属 Workbench。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseHandoffProjection {

    private final WorkbenchPhase sourcePhase;
    private final String summary;
    private final List<DecisionView> decisions;
    private final List<OpenQuestionView> openQuestions;
    private final List<DocumentReferenceView> pinnedFiles;
    private final List<RunReferenceView> referencedRuns;
    private final long version;
    private final String contentHash;
    private final long updatedAt;
    private final boolean readOnly;

    private PhaseHandoffProjection(
            WorkbenchPhase sourcePhase, String summary,
            List<Decision> decisions, List<OpenQuestion> openQuestions,
            List<DocumentReference> pinnedFiles,
            List<WorkbenchRunReference> referencedRuns,
            long version, String contentHash, long updatedAt,
            boolean readOnly) {
        this.sourcePhase = sourcePhase;
        this.summary = summary;
        this.decisions = decisionViews(decisions);
        this.openQuestions = questionViews(openQuestions);
        this.pinnedFiles = documentViews(pinnedFiles);
        this.referencedRuns = runViews(referencedRuns);
        this.version = version;
        this.contentHash = contentHash;
        this.updatedAt = updatedAt;
        this.readOnly = readOnly;
    }

    public static PhaseHandoffProjection from(
            PhaseHandoff handoff, boolean readOnly) {
        if (handoff == null) {
            throw new IllegalArgumentException("phase handoff is required");
        }
        return new PhaseHandoffProjection(
                handoff.getSourcePhase(), handoff.getSummary(),
                handoff.getDecisions(), handoff.getOpenQuestions(),
                handoff.getPinnedFiles(), handoff.getReferencedRuns(),
                handoff.getVersion(), handoff.getContentHash(),
                handoff.getUpdatedAt().toEpochMilli(), readOnly);
    }

    public static PhaseHandoffProjection from(
            PhaseHandoffRevision revision, boolean readOnly) {
        if (revision == null) {
            throw new IllegalArgumentException(
                    "phase handoff revision is required");
        }
        return new PhaseHandoffProjection(
                revision.getSourcePhase(), revision.getSummary(),
                revision.getDecisions(), revision.getOpenQuestions(),
                revision.getPinnedFiles(), revision.getReferencedRuns(),
                revision.getVersion(), revision.getContentHash(),
                revision.getUpdatedAt().toEpochMilli(), readOnly);
    }

    private static List<DecisionView> decisionViews(List<Decision> values) {
        List<DecisionView> result = new ArrayList<DecisionView>(values.size());
        for (Decision value : values) {
            result.add(new DecisionView(value.getText(), value.getRationale()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<OpenQuestionView> questionViews(
            List<OpenQuestion> values) {
        List<OpenQuestionView> result =
                new ArrayList<OpenQuestionView>(values.size());
        for (OpenQuestion value : values) {
            result.add(new OpenQuestionView(
                    value.getText(), value.getOwnerHint()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<DocumentReferenceView> documentViews(
            List<DocumentReference> values) {
        List<DocumentReferenceView> result =
                new ArrayList<DocumentReferenceView>(values.size());
        for (DocumentReference value : values) {
            result.add(new DocumentReferenceView(
                    value.getRepositoryKey(), value.getRelativePath()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<RunReferenceView> runViews(
            List<WorkbenchRunReference> values) {
        List<RunReferenceView> result =
                new ArrayList<RunReferenceView>(values.size());
        for (WorkbenchRunReference value : values) {
            result.add(new RunReferenceView(
                    value.getRunId(), value.getPhase(),
                    value.getSafeSummary()));
        }
        return Collections.unmodifiableList(result);
    }

    /** 公开的 Decision 字段。 */
    @Getter
    @EqualsAndHashCode
    public static final class DecisionView {
        private final String text;
        private final String rationale;

        private DecisionView(String text, String rationale) {
            this.text = text;
            this.rationale = rationale;
        }
    }

    /** 公开的 Open Question 字段。 */
    @Getter
    @EqualsAndHashCode
    public static final class OpenQuestionView {
        private final String text;
        private final String ownerHint;

        private OpenQuestionView(String text, String ownerHint) {
            this.text = text;
            this.ownerHint = ownerHint;
        }
    }

    /** 公开的逻辑文件引用。 */
    @Getter
    @EqualsAndHashCode
    public static final class DocumentReferenceView {
        private final String repositoryKey;
        private final String relativePath;

        private DocumentReferenceView(
                String repositoryKey, String relativePath) {
            this.repositoryKey = repositoryKey;
            this.relativePath = relativePath;
        }
    }

    /** 不含 WorkbenchId 的公开 Run 引用。 */
    @Getter
    public static final class RunReferenceView {
        private final String runId;
        private final WorkbenchPhase phase;
        private final String safeSummary;

        private RunReferenceView(
                String runId, WorkbenchPhase phase, String safeSummary) {
            this.runId = runId;
            this.phase = phase;
            this.safeSummary = safeSummary;
        }
    }
}
