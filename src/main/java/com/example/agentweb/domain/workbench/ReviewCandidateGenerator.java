package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.workspace.RepositoryScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 当前 Review Phase 公开消息到结构化 Candidate 的确定性领域策略。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class ReviewCandidateGenerator {

    private static final String REVIEW = "review:";
    private static final String REVIEW_ZH = "审查意见：";
    private static final String IMPACT = "impact:";
    private static final String IMPACT_ZH = "影响：";
    private static final String SUGGESTED_CHANGE = "suggested change:";
    private static final String SUGGESTED_CHANGE_ZH = "重构方案：";
    private static final String AFFECTED_FILE = "affected file:";
    private static final String AFFECTED_FILE_ZH = "影响文件：";
    private static final String REQUIRED_TEST = "required test:";
    private static final String REQUIRED_TEST_ZH = "受影响测试：";
    private static final Pattern POSIX_ABSOLUTE_PATH = Pattern.compile(
            "(^|[\\s\\(\\[\\{=\\\"'])/(?!/)[^\\s]+"
    );
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "(^|[\\s\\(\\[\\{=\\\"'])[A-Za-z]:[\\\\/][^\\s]+"
    );

    public ReviewCandidate generate(
            OwnerReference actor, Workbench workbench,
            Optional<ReviewOpinion> baseOpinion,
            ReviewCandidateConversation conversation) {
        if (actor == null || workbench == null || baseOpinion == null
                || conversation == null) {
            throw new IllegalArgumentException(
                    "review candidate generation inputs are required");
        }
        workbench.requireOperableBy(actor);
        PhaseConversationReference current = requireCurrentConversation(workbench);
        requireSameConversation(current, conversation);
        long baseVersion = requireMatchingBase(
                workbench.getId(), baseOpinion);
        List<ReviewCandidateItem> items = extract(
                workbench.getRepositoryScope(), conversation.getMessages());
        return new ReviewCandidate(
                actor, workbench.getId(), current.getConversationId(),
                current.getGeneration(), baseVersion,
                conversation.getMessages().size(),
                ReviewCandidateStrategy.DETERMINISTIC_PUBLIC_REVIEW_MESSAGES_V1,
                items);
    }

    private PhaseConversationReference requireCurrentConversation(
            Workbench workbench) {
        PhaseConversationReference current = workbench
                .phase(WorkbenchPhase.REVIEW_REFACTOR)
                .currentConversation();
        if (current == null) {
            throw conversationChanged();
        }
        return current;
    }

    private void requireSameConversation(
            PhaseConversationReference current,
            ReviewCandidateConversation captured) {
        if (!current.getConversationId().equals(captured.getConversationId())
                || current.getGeneration() != captured.getGeneration()) {
            throw conversationChanged();
        }
    }

    private long requireMatchingBase(
            WorkbenchId workbenchId,
            Optional<ReviewOpinion> baseOpinion) {
        if (!baseOpinion.isPresent()) {
            return 0L;
        }
        ReviewOpinion opinion = baseOpinion.get();
        if (!workbenchId.equals(opinion.getWorkbenchId())
                || opinion.getPhase() != WorkbenchPhase.REVIEW_REFACTOR) {
            throw new IllegalArgumentException(
                    "review candidate base opinion must match its workbench");
        }
        return opinion.getVersion();
    }

    private List<ReviewCandidateItem> extract(
            RepositoryScope scope,
            List<ReviewCandidateMessage> messages) {
        List<ReviewCandidateItem> result =
                new ArrayList<ReviewCandidateItem>();
        String latestAssistant = null;
        for (ReviewCandidateMessage message : messages) {
            if (message.getRole() != ReviewCandidateMessage.Role.ASSISTANT) {
                continue;
            }
            latestAssistant = message.getContent();
            parseMessage(message.getContent(), scope, result);
        }
        if (result.isEmpty()
                && latestAssistant != null
                && !latestAssistant.trim().isEmpty()) {
            String safeFallback = safeBounded(
                    latestAssistant, 2000, scope);
            if (safeFallback != null) {
                result.add(ReviewCandidateItem.propose(
                        safeFallback, "", "",
                        new ArrayList<DocumentReference>(),
                        new ArrayList<String>()));
            }
        }
        return result;
    }

    private void parseMessage(
            String content, RepositoryScope scope,
            List<ReviewCandidateItem> result) {
        MutableItem current = null;
        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = stripListMarker(rawLine.trim());
            String normalized = line.toLowerCase(Locale.ROOT);
            String finding = labeledValue(
                    line, normalized, REVIEW, REVIEW_ZH);
            if (finding != null) {
                addCurrent(current, result);
                String safeFinding = safeBounded(finding, 2000, scope);
                current = result.size() < ReviewCandidate.MAX_ITEMS
                        && safeFinding != null
                        ? new MutableItem(safeFinding) : null;
                continue;
            }
            if (current == null) {
                continue;
            }
            String impact = labeledValue(
                    line, normalized, IMPACT, IMPACT_ZH);
            if (impact != null) {
                String safeImpact = safeBounded(impact, 4000, scope);
                if (safeImpact != null) {
                    current.impact = safeImpact;
                }
                continue;
            }
            String change = labeledValue(
                    line, normalized,
                    SUGGESTED_CHANGE, SUGGESTED_CHANGE_ZH);
            if (change != null) {
                String safeChange = safeBounded(change, 4000, scope);
                if (safeChange != null) {
                    current.suggestedChange = safeChange;
                }
                continue;
            }
            String file = labeledValue(
                    line, normalized, AFFECTED_FILE, AFFECTED_FILE_ZH);
            if (file != null && current.affectedFiles.size() < 50) {
                addStructuredFile(file, scope, current.affectedFiles);
                continue;
            }
            String test = labeledValue(
                    line, normalized, REQUIRED_TEST, REQUIRED_TEST_ZH);
            if (test != null && current.suggestedTests.size() < 20) {
                String safeTest = safeBounded(test, 1000, scope);
                if (safeTest != null) {
                    current.suggestedTests.add(safeTest);
                }
            }
        }
        addCurrent(current, result);
    }

    private void addCurrent(
            MutableItem current, List<ReviewCandidateItem> result) {
        if (current == null || result.size() >= ReviewCandidate.MAX_ITEMS) {
            return;
        }
        result.add(ReviewCandidateItem.propose(
                current.finding, current.impact,
                current.suggestedChange,
                new ArrayList<DocumentReference>(current.affectedFiles),
                new ArrayList<String>(current.suggestedTests)));
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
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
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
            // 不可信 Agent 文本中的无效结构引用不能扩大 Repository Scope。
        }
    }

    private String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximum
                ? normalized : normalized.substring(0, maximum);
    }

    private String safeBounded(
            String value, int maximum, RepositoryScope scope) {
        String normalized = bounded(value, maximum);
        return containsAbsolutePath(normalized, scope) ? null : normalized;
    }

    private boolean containsAbsolutePath(
            String value, RepositoryScope scope) {
        if (value.contains(scope.getWorkspaceRoot())) {
            return true;
        }
        for (String repositoryRoot : scope.repositoryRoots()) {
            if (value.contains(repositoryRoot)) {
                return true;
            }
        }
        return POSIX_ABSOLUTE_PATH.matcher(value).find()
                || WINDOWS_ABSOLUTE_PATH.matcher(value).find();
    }

    private WorkbenchDomainException conversationChanged() {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.CONVERSATION_CONFLICT,
                "review candidate source conversation changed");
    }

    private static final class MutableItem {
        private final String finding;
        private String impact = "";
        private String suggestedChange = "";
        private final Set<DocumentReference> affectedFiles =
                new LinkedHashSet<DocumentReference>();
        private final Set<String> suggestedTests =
                new LinkedHashSet<String>();

        private MutableItem(String finding) {
            this.finding = finding;
        }
    }
}
