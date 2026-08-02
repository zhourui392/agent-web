package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 根据当前 Phase 公开对话生成的非持久化 Handoff 建议。
 *
 * <p>该值对象只绑定生成依据，不属于 PhaseHandoff 生命周期，不能自动成为人工事实。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseHandoffCandidate {

    private final OwnerReference owner;
    private final WorkbenchId workbenchId;
    private final WorkbenchPhase sourcePhase;
    private final String conversationId;
    private final int conversationGeneration;
    private final long baseHandoffVersion;
    private final int sourceMessageCount;
    private final HandoffCandidateStrategy strategy;
    private final String summary;
    private final List<DecisionCandidate> decisions;
    private final List<OpenQuestionCandidate> openQuestions;
    private final List<DocumentReference> pinnedFiles;
    private final List<WorkbenchRunReference> referencedRuns;

    PhaseHandoffCandidate(
            OwnerReference owner, WorkbenchId workbenchId,
            WorkbenchPhase sourcePhase, String conversationId,
            int conversationGeneration, long baseHandoffVersion,
            int sourceMessageCount, HandoffCandidateStrategy strategy,
            String summary, List<DecisionCandidate> decisions,
            List<OpenQuestionCandidate> openQuestions,
            List<DocumentReference> pinnedFiles,
            List<WorkbenchRunReference> referencedRuns) {
        if (owner == null || workbenchId == null || sourcePhase == null
                || strategy == null) {
            throw new IllegalArgumentException(
                    "handoff candidate binding is required");
        }
        if (conversationGeneration < 0 || baseHandoffVersion < 0L
                || sourceMessageCount < 0
                || sourceMessageCount > HandoffCandidateConversation.MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "handoff candidate binding counters are invalid");
        }
        this.owner = owner;
        this.workbenchId = workbenchId;
        this.sourcePhase = sourcePhase;
        this.conversationId = DomainText.require(
                conversationId, "handoff candidate conversation id", 128);
        this.conversationGeneration = conversationGeneration;
        this.baseHandoffVersion = baseHandoffVersion;
        this.sourceMessageCount = sourceMessageCount;
        this.strategy = strategy;
        this.summary = WorkbenchText.allowEmptyUntrustedText(
                summary, "handoff candidate summary", 8000);
        this.decisions = immutableCopy(
                decisions, 50, "handoff candidate decisions");
        this.openQuestions = immutableCopy(
                openQuestions, 50, "handoff candidate open questions");
        this.pinnedFiles = requireUniquePinnedFiles(
                immutableCopy(pinnedFiles, 100,
                        "handoff candidate pinned files"));
        this.referencedRuns = requireBoundRuns(
                immutableCopy(referencedRuns, 50,
                        "handoff candidate referenced runs"),
                workbenchId, sourcePhase);
    }

    private static <T> List<T> immutableCopy(
            List<T> values, int maximum, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        if (values.size() > maximum) {
            throw new IllegalArgumentException(
                    name + " must contain at most " + maximum + " items");
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    private static List<DocumentReference> requireUniquePinnedFiles(
            List<DocumentReference> values) {
        if (new HashSet<DocumentReference>(values).size() != values.size()) {
            throw new IllegalArgumentException(
                    "handoff candidate pinned files must be unique");
        }
        return values;
    }

    private static List<WorkbenchRunReference> requireBoundRuns(
            List<WorkbenchRunReference> values, WorkbenchId workbenchId,
            WorkbenchPhase sourcePhase) {
        Set<String> runIds = new HashSet<String>();
        for (WorkbenchRunReference run : values) {
            if (!workbenchId.equals(run.getWorkbenchId())
                    || sourcePhase != run.getPhase()
                    || !runIds.add(run.getRunId())) {
                throw new IllegalArgumentException(
                        "handoff candidate runs must be unique and bound to its phase");
            }
        }
        return values;
    }

    /** Candidate Decision，不携带人工 CONFIRMED 语义。 */
    @Getter
    public static final class DecisionCandidate {
        private final String text;
        private final String rationale;

        DecisionCandidate(String text, String rationale) {
            this.text = WorkbenchText.requireUntrustedText(
                    text, "handoff candidate decision", 2000);
            this.rationale = WorkbenchText.optionalUntrustedText(
                    rationale, "handoff candidate decision rationale", 2000);
        }
    }

    /** Candidate Open Question，不自动推导 resolved。 */
    @Getter
    public static final class OpenQuestionCandidate {
        private final String text;
        private final String ownerHint;

        OpenQuestionCandidate(String text, String ownerHint) {
            this.text = WorkbenchText.requireUntrustedText(
                    text, "handoff candidate open question", 2000);
            this.ownerHint = WorkbenchText.optionalUntrustedText(
                    ownerHint, "handoff candidate question owner", 256);
        }
    }
}
