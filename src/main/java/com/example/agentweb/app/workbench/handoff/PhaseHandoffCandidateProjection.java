package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.PhaseHandoffCandidate;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 非持久化 Handoff Candidate 的 Owner-safe 应用投影。
 *
 * <p>有意不暴露 owner、workbenchId 和 conversationId，只公开 UI 合并所需绑定版本。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseHandoffCandidateProjection {

    private final WorkbenchPhase sourcePhase;
    private final long baseHandoffVersion;
    private final int conversationGeneration;
    private final int sourceMessageCount;
    private final String strategy;
    private final String summary;
    private final List<DecisionView> decisions;
    private final List<OpenQuestionView> openQuestions;
    private final List<DocumentReferenceView> pinnedFiles;
    private final List<RunReferenceView> referencedRuns;

    private PhaseHandoffCandidateProjection(PhaseHandoffCandidate candidate) {
        this.sourcePhase = candidate.getSourcePhase();
        this.baseHandoffVersion = candidate.getBaseHandoffVersion();
        this.conversationGeneration = candidate.getConversationGeneration();
        this.sourceMessageCount = candidate.getSourceMessageCount();
        this.strategy = candidate.getStrategy().name();
        this.summary = candidate.getSummary();
        this.decisions = decisions(candidate.getDecisions());
        this.openQuestions = questions(candidate.getOpenQuestions());
        this.pinnedFiles = files(candidate.getPinnedFiles());
        this.referencedRuns = runs(candidate.getReferencedRuns());
    }

    public static PhaseHandoffCandidateProjection from(
            PhaseHandoffCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "phase handoff candidate is required");
        }
        return new PhaseHandoffCandidateProjection(candidate);
    }

    private static List<DecisionView> decisions(
            List<PhaseHandoffCandidate.DecisionCandidate> values) {
        List<DecisionView> result = new ArrayList<DecisionView>(values.size());
        for (PhaseHandoffCandidate.DecisionCandidate value : values) {
            result.add(new DecisionView(value.getText(), value.getRationale()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<OpenQuestionView> questions(
            List<PhaseHandoffCandidate.OpenQuestionCandidate> values) {
        List<OpenQuestionView> result =
                new ArrayList<OpenQuestionView>(values.size());
        for (PhaseHandoffCandidate.OpenQuestionCandidate value : values) {
            result.add(new OpenQuestionView(
                    value.getText(), value.getOwnerHint()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<DocumentReferenceView> files(
            List<DocumentReference> values) {
        List<DocumentReferenceView> result =
                new ArrayList<DocumentReferenceView>(values.size());
        for (DocumentReference value : values) {
            result.add(new DocumentReferenceView(
                    value.getRepositoryKey(), value.getRelativePath()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<RunReferenceView> runs(
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

    /** Candidate Decision 公开字段。 */
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

    /** Candidate Open Question 公开字段。 */
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

    /** Candidate Pinned File 公开结构引用。 */
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

    /** Candidate Referenced Run 的公开安全元数据。 */
    @Getter
    @EqualsAndHashCode
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
