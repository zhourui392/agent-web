package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.handoff.PhaseHandoffCandidateProjection;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 浏览器内逐项确认使用的非持久化 Handoff Candidate 响应。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseHandoffCandidateResponse {

    private final WorkbenchPhase sourcePhase;
    private final long baseHandoffVersion;
    private final int conversationGeneration;
    private final int sourceMessageCount;
    private final String strategy;
    private final String summary;
    private final List<DecisionResponse> decisions;
    private final List<OpenQuestionResponse> openQuestions;
    private final List<DocumentReferenceResponse> pinnedFiles;
    private final List<RunReferenceResponse> referencedRuns;

    private PhaseHandoffCandidateResponse(
            PhaseHandoffCandidateProjection projection) {
        this.sourcePhase = projection.getSourcePhase();
        this.baseHandoffVersion = projection.getBaseHandoffVersion();
        this.conversationGeneration = projection.getConversationGeneration();
        this.sourceMessageCount = projection.getSourceMessageCount();
        this.strategy = projection.getStrategy();
        this.summary = projection.getSummary();
        this.decisions = decisions(projection.getDecisions());
        this.openQuestions = questions(projection.getOpenQuestions());
        this.pinnedFiles = files(projection.getPinnedFiles());
        this.referencedRuns = runs(projection.getReferencedRuns());
    }

    public static PhaseHandoffCandidateResponse from(
            PhaseHandoffCandidateProjection projection) {
        if (projection == null) {
            throw new IllegalArgumentException(
                    "phase handoff candidate projection is required");
        }
        return new PhaseHandoffCandidateResponse(projection);
    }

    private static List<DecisionResponse> decisions(
            List<PhaseHandoffCandidateProjection.DecisionView> values) {
        List<DecisionResponse> result =
                new ArrayList<DecisionResponse>(values.size());
        for (PhaseHandoffCandidateProjection.DecisionView value : values) {
            result.add(new DecisionResponse(
                    value.getText(), value.getRationale()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<OpenQuestionResponse> questions(
            List<PhaseHandoffCandidateProjection.OpenQuestionView> values) {
        List<OpenQuestionResponse> result =
                new ArrayList<OpenQuestionResponse>(values.size());
        for (PhaseHandoffCandidateProjection.OpenQuestionView value : values) {
            result.add(new OpenQuestionResponse(
                    value.getText(), value.getOwnerHint()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<DocumentReferenceResponse> files(
            List<PhaseHandoffCandidateProjection.DocumentReferenceView> values) {
        List<DocumentReferenceResponse> result =
                new ArrayList<DocumentReferenceResponse>(values.size());
        for (PhaseHandoffCandidateProjection.DocumentReferenceView value
                : values) {
            result.add(new DocumentReferenceResponse(
                    value.getRepositoryKey(), value.getRelativePath()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<RunReferenceResponse> runs(
            List<PhaseHandoffCandidateProjection.RunReferenceView> values) {
        List<RunReferenceResponse> result =
                new ArrayList<RunReferenceResponse>(values.size());
        for (PhaseHandoffCandidateProjection.RunReferenceView value : values) {
            result.add(new RunReferenceResponse(
                    value.getRunId(), value.getPhase(),
                    value.getSafeSummary()));
        }
        return Collections.unmodifiableList(result);
    }

    /** Candidate Decision DTO。 */
    @Getter
    public static final class DecisionResponse {
        private final String text;
        private final String rationale;

        private DecisionResponse(String text, String rationale) {
            this.text = text;
            this.rationale = rationale;
        }
    }

    /** Candidate Open Question DTO。 */
    @Getter
    public static final class OpenQuestionResponse {
        private final String text;
        private final String ownerHint;

        private OpenQuestionResponse(String text, String ownerHint) {
            this.text = text;
            this.ownerHint = ownerHint;
        }
    }

    /** Candidate Pinned File DTO。 */
    @Getter
    public static final class DocumentReferenceResponse {
        private final String repositoryKey;
        private final String relativePath;

        private DocumentReferenceResponse(
                String repositoryKey, String relativePath) {
            this.repositoryKey = repositoryKey;
            this.relativePath = relativePath;
        }
    }

    /** Candidate Referenced Run DTO。 */
    @Getter
    public static final class RunReferenceResponse {
        private final String runId;
        private final WorkbenchPhase phase;
        private final String safeSummary;

        private RunReferenceResponse(
                String runId, WorkbenchPhase phase, String safeSummary) {
            this.runId = runId;
            this.phase = phase;
            this.safeSummary = safeSummary;
        }
    }
}
