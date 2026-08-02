package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.workspace.RepositoryScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 从当前 Phase 的有界公开消息投影生成 Handoff Candidate 的确定性领域策略。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class PhaseHandoffCandidateGenerator {

    private static final String DECISION = "decision:";
    private static final String DECISION_ZH = "决定：";
    private static final String OPEN_QUESTION = "open question:";
    private static final String OPEN_QUESTION_ZH = "未决问题：";
    private static final String PINNED_FILE = "pinned file:";
    private static final String PINNED_FILE_ZH = "关键文件：";

    public PhaseHandoffCandidate generate(
            OwnerReference actor, Workbench workbench,
            WorkbenchPhase sourcePhase, Optional<PhaseHandoff> baseHandoff,
            HandoffCandidateConversation conversation) {
        if (actor == null || workbench == null || sourcePhase == null
                || baseHandoff == null || conversation == null) {
            throw new IllegalArgumentException(
                    "handoff candidate generation inputs are required");
        }
        workbench.requireOperableBy(actor);
        PhaseConversationReference current =
                requireCurrentConversation(workbench, sourcePhase);
        requireSameConversation(current, conversation);
        long baseVersion = requireMatchingBase(
                workbench.getId(), sourcePhase, baseHandoff);

        CandidateContent content = extract(
                workbench.getId(), sourcePhase,
                workbench.getRepositoryScope(), conversation.getMessages());
        return new PhaseHandoffCandidate(
                actor, workbench.getId(), sourcePhase,
                current.getConversationId(), current.getGeneration(),
                baseVersion, conversation.getMessages().size(),
                HandoffCandidateStrategy.DETERMINISTIC_PUBLIC_MESSAGES_V1,
                content.summary, content.decisions, content.openQuestions,
                content.pinnedFiles, content.referencedRuns);
    }

    private PhaseConversationReference requireCurrentConversation(
            Workbench workbench, WorkbenchPhase sourcePhase) {
        PhaseConversationReference current =
                workbench.phase(sourcePhase).currentConversation();
        if (current == null) {
            throw conversationChanged();
        }
        return current;
    }

    private void requireSameConversation(
            PhaseConversationReference current,
            HandoffCandidateConversation captured) {
        if (!current.getConversationId().equals(captured.getConversationId())
                || current.getGeneration() != captured.getGeneration()) {
            throw conversationChanged();
        }
    }

    private long requireMatchingBase(
            WorkbenchId workbenchId, WorkbenchPhase sourcePhase,
            Optional<PhaseHandoff> baseHandoff) {
        if (!baseHandoff.isPresent()) {
            return 0L;
        }
        PhaseHandoff base = baseHandoff.get();
        if (!workbenchId.equals(base.getWorkbenchId())
                || sourcePhase != base.getSourcePhase()) {
            throw new IllegalArgumentException(
                    "handoff candidate base must match workbench and phase");
        }
        return base.getVersion();
    }

    private CandidateContent extract(
            WorkbenchId workbenchId, WorkbenchPhase sourcePhase,
            RepositoryScope repositoryScope,
            List<HandoffCandidateMessage> messages) {
        String summary = "";
        Set<String> decisions = new LinkedHashSet<String>();
        Set<String> questions = new LinkedHashSet<String>();
        Set<DocumentReference> files = new LinkedHashSet<DocumentReference>();
        Map<String, WorkbenchRunReference> runs =
                new LinkedHashMap<String, WorkbenchRunReference>();
        for (HandoffCandidateMessage message : messages) {
            if (message.getRole() != HandoffCandidateMessage.Role.ASSISTANT) {
                continue;
            }
            summary = bounded(message.getContent(), 8000);
            parseLabeledContent(
                    message.getContent(), repositoryScope,
                    decisions, questions, files);
            if (message.getRunId() != null) {
                String runId = message.getRunId();
                runs.putIfAbsent(runId, WorkbenchRunReference.of(
                        runId, workbenchId, sourcePhase,
                        "Run " + runId + " (" + sourcePhase.name() + ")"));
            }
        }
        return new CandidateContent(
                summary, decisionCandidates(decisions),
                questionCandidates(questions),
                limitedFiles(files), limitedRuns(runs));
    }

    private void parseLabeledContent(
            String content, RepositoryScope repositoryScope,
            Set<String> decisions, Set<String> questions,
            Set<DocumentReference> files) {
        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = stripListMarker(rawLine.trim());
            String normalized = line.toLowerCase(Locale.ROOT);
            String decision = labeledValue(
                    line, normalized, DECISION, DECISION_ZH);
            if (decision != null && decisions.size() < 50) {
                decisions.add(bounded(decision, 2000));
                continue;
            }
            String question = labeledValue(
                    line, normalized, OPEN_QUESTION, OPEN_QUESTION_ZH);
            if (question != null && questions.size() < 50) {
                questions.add(bounded(question, 2000));
                continue;
            }
            String file = labeledValue(
                    line, normalized, PINNED_FILE, PINNED_FILE_ZH);
            if (file != null && files.size() < 100) {
                addStructuredFile(file, repositoryScope, files);
            }
        }
    }

    private String stripListMarker(String value) {
        if (value.startsWith("- ") || value.startsWith("* ")) {
            return value.substring(2).trim();
        }
        return value;
    }

    private String labeledValue(
            String original, String normalized,
            String englishPrefix, String chinesePrefix) {
        if (normalized.startsWith(englishPrefix)) {
            return nonEmpty(original.substring(englishPrefix.length()));
        }
        if (original.startsWith(chinesePrefix)) {
            return nonEmpty(original.substring(chinesePrefix.length()));
        }
        return null;
    }

    private String nonEmpty(String value) {
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private void addStructuredFile(
            String value, RepositoryScope scope,
            Set<DocumentReference> files) {
        int separator = value.indexOf("::");
        if (separator <= 0 || separator == value.length() - 2
                || value.indexOf("::", separator + 2) >= 0) {
            return;
        }
        String repositoryKey = value.substring(0, separator).trim();
        String relativePath = value.substring(separator + 2).trim();
        if (!scope.containsRepository(repositoryKey)) {
            return;
        }
        try {
            files.add(DocumentReference.of(repositoryKey, relativePath));
        } catch (IllegalArgumentException ignoredMalformedReference) {
            // Candidate 忽略不可信 Agent 文本中的无效结构引用，不扩大文件边界。
        }
    }

    private List<PhaseHandoffCandidate.DecisionCandidate> decisionCandidates(
            Set<String> values) {
        List<PhaseHandoffCandidate.DecisionCandidate> result =
                new ArrayList<PhaseHandoffCandidate.DecisionCandidate>();
        for (String value : values) {
            result.add(new PhaseHandoffCandidate.DecisionCandidate(value, null));
        }
        return result;
    }

    private List<PhaseHandoffCandidate.OpenQuestionCandidate> questionCandidates(
            Set<String> values) {
        List<PhaseHandoffCandidate.OpenQuestionCandidate> result =
                new ArrayList<PhaseHandoffCandidate.OpenQuestionCandidate>();
        for (String value : values) {
            result.add(new PhaseHandoffCandidate.OpenQuestionCandidate(value, null));
        }
        return result;
    }

    private List<DocumentReference> limitedFiles(
            Set<DocumentReference> values) {
        List<DocumentReference> result = new ArrayList<DocumentReference>(values);
        Collections.sort(result);
        return result;
    }

    private List<WorkbenchRunReference> limitedRuns(
            Map<String, WorkbenchRunReference> values) {
        return new ArrayList<WorkbenchRunReference>(values.values());
    }

    private String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximum
                ? normalized : normalized.substring(0, maximum);
    }

    private WorkbenchDomainException conversationChanged() {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.CONVERSATION_CONFLICT,
                "handoff candidate source conversation changed");
    }

    private static final class CandidateContent {
        private final String summary;
        private final List<PhaseHandoffCandidate.DecisionCandidate> decisions;
        private final List<PhaseHandoffCandidate.OpenQuestionCandidate> openQuestions;
        private final List<DocumentReference> pinnedFiles;
        private final List<WorkbenchRunReference> referencedRuns;

        private CandidateContent(
                String summary,
                List<PhaseHandoffCandidate.DecisionCandidate> decisions,
                List<PhaseHandoffCandidate.OpenQuestionCandidate> openQuestions,
                List<DocumentReference> pinnedFiles,
                List<WorkbenchRunReference> referencedRuns) {
            this.summary = summary;
            this.decisions = decisions;
            this.openQuestions = openQuestions;
            this.pinnedFiles = pinnedFiles;
            this.referencedRuns = referencedRuns;
        }
    }
}
