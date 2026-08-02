package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workspace.RepositoryScope;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 人工维护的结构化阶段交接聚合。
 *
 * <p>Agent Candidate 只能作为 Application 输入，只有显式 create/update 才改变聚合事实。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseHandoff {

    public static final String CONTENT_HASH_SCHEMA = "workbench-phase-handoff@1";
    private static final int MAX_DECISIONS = 50;
    private static final int MAX_OPEN_QUESTIONS = 50;
    private static final int MAX_PINNED_FILES = 100;
    private static final int MAX_REFERENCED_RUNS = 50;
    private static final int MAX_SERIALIZED_BYTES = 256 * 1024;

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase sourcePhase;
    private String summary;
    private List<Decision> decisions;
    private List<OpenQuestion> openQuestions;
    private List<DocumentReference> pinnedFiles;
    private List<WorkbenchRunReference> referencedRuns;
    private String contentHash;
    private OwnerReference updatedBy;
    private Instant updatedAt;
    private long version;

    private PhaseHandoff(WorkbenchId workbenchId, WorkbenchPhase sourcePhase,
                         HandoffContent content, OwnerReference updatedBy,
                         Instant updatedAt, long version) {
        if (workbenchId == null || sourcePhase == null || updatedBy == null) {
            throw new IllegalArgumentException(
                    "handoff workbench, phase and updater are required");
        }
        if (version < 0L) {
            throw new IllegalArgumentException("handoff version must not be negative");
        }
        this.workbenchId = workbenchId;
        this.sourcePhase = sourcePhase;
        apply(content);
        this.updatedBy = updatedBy;
        this.updatedAt = DomainText.requireTime(updatedAt, "handoff updated at");
        this.version = version;
    }

    public static PhaseHandoff create(
            WorkbenchId workbenchId, WorkbenchPhase sourcePhase,
            String summary, List<Decision> decisions,
            List<OpenQuestion> openQuestions, List<DocumentReference> pinnedFiles,
            List<WorkbenchRunReference> referencedRuns, RepositoryScope repositoryScope,
            OwnerReference updatedBy, Instant updatedAt) {
        HandoffContent content = HandoffContent.validate(
                workbenchId, summary, decisions, openQuestions,
                pinnedFiles, referencedRuns, repositoryScope);
        return new PhaseHandoff(
                workbenchId, sourcePhase, content, updatedBy, updatedAt, 0L);
    }

    public static PhaseHandoff restore(
            WorkbenchId workbenchId, WorkbenchPhase sourcePhase,
            String summary, List<Decision> decisions,
            List<OpenQuestion> openQuestions, List<DocumentReference> pinnedFiles,
            List<WorkbenchRunReference> referencedRuns, String contentHash,
            OwnerReference updatedBy, Instant updatedAt, long version,
            RepositoryScope repositoryScope) {
        HandoffContent content = HandoffContent.validate(
                workbenchId, summary, decisions, openQuestions,
                pinnedFiles, referencedRuns, repositoryScope);
        String restoredHash = DomainText.requireSha256(contentHash, "handoff content hash");
        if (!content.contentHash.equals(restoredHash)) {
            throw new IllegalArgumentException(
                    "restored handoff content hash does not match its content");
        }
        return new PhaseHandoff(
                workbenchId, sourcePhase, content, updatedBy, updatedAt, version);
    }

    public void update(
            long expectedVersion, String summary, List<Decision> decisions,
            List<OpenQuestion> openQuestions, List<DocumentReference> pinnedFiles,
            List<WorkbenchRunReference> referencedRuns, RepositoryScope repositoryScope,
            OwnerReference actor, Instant now) {
        if (expectedVersion != version) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "handoff expected version does not match current version");
        }
        if (actor == null) {
            throw new IllegalArgumentException("handoff updater must not be null");
        }
        Instant updateTime = DomainText.requireTime(now, "handoff updated at");
        if (updateTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "handoff updated time must not move backwards");
        }
        HandoffContent next = HandoffContent.validate(
                workbenchId, summary, decisions, openQuestions,
                pinnedFiles, referencedRuns, repositoryScope);
        apply(next);
        updatedBy = actor;
        updatedAt = updateTime;
        version++;
    }

    /**
     * 将当前精确版本作为下游阶段首次接收的来源事实。
     *
     * <p>调用方传入的版本来自用户预览；若预览后 Handoff 已更新，必须以并发冲突拒绝，
     * 不能把用户未确认的新内容静默注入 Run。</p>
     */
    public HandoffReception acceptInto(
            WorkbenchPhase targetPhase, long expectedSourceVersion,
            OwnerReference actor, Instant acceptedAt) {
        if (expectedSourceVersion != version) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "handoff source changed after downstream preview");
        }
        return HandoffReception.accept(
                workbenchId, targetPhase, sourcePhase, version, contentHash,
                actor, acceptedAt);
    }

    private void apply(HandoffContent content) {
        this.summary = content.summary;
        this.decisions = content.decisions;
        this.openQuestions = content.openQuestions;
        this.pinnedFiles = content.pinnedFiles;
        this.referencedRuns = content.referencedRuns;
        this.contentHash = content.contentHash;
    }

    private static final class HandoffContent {

        private final String summary;
        private final List<Decision> decisions;
        private final List<OpenQuestion> openQuestions;
        private final List<DocumentReference> pinnedFiles;
        private final List<WorkbenchRunReference> referencedRuns;
        private final String contentHash;

        private HandoffContent(String summary, List<Decision> decisions,
                               List<OpenQuestion> openQuestions,
                               List<DocumentReference> pinnedFiles,
                               List<WorkbenchRunReference> referencedRuns,
                               String contentHash) {
            this.summary = summary;
            this.decisions = Collections.unmodifiableList(decisions);
            this.openQuestions = Collections.unmodifiableList(openQuestions);
            this.pinnedFiles = Collections.unmodifiableList(pinnedFiles);
            this.referencedRuns = Collections.unmodifiableList(referencedRuns);
            this.contentHash = contentHash;
        }

        private static HandoffContent validate(
                WorkbenchId workbenchId, String summary,
                List<Decision> decisions, List<OpenQuestion> openQuestions,
                List<DocumentReference> pinnedFiles,
                List<WorkbenchRunReference> referencedRuns,
                RepositoryScope repositoryScope) {
            if (workbenchId == null || repositoryScope == null) {
                throw new IllegalArgumentException(
                        "handoff workbench and repository scope are required");
            }
            String normalizedSummary = WorkbenchText.allowEmptyUntrustedText(
                    summary, "handoff summary", 8000);
            List<Decision> decisionList = copyAndLimit(
                    decisions, MAX_DECISIONS, "handoff decisions");
            List<OpenQuestion> questionList = copyAndLimit(
                    openQuestions, MAX_OPEN_QUESTIONS, "handoff open questions");
            List<DocumentReference> fileList = copyAndLimit(
                    pinnedFiles, MAX_PINNED_FILES, "handoff pinned files");
            List<WorkbenchRunReference> runList = copyAndLimit(
                    referencedRuns, MAX_REFERENCED_RUNS, "handoff referenced runs");

            HandoffSecretPolicy.requireSafe(
                    normalizedSummary, decisionList, questionList, runList);
            requirePinnedFilesInScope(fileList, repositoryScope);
            requireRunsBelongToWorkbench(runList, workbenchId);
            fileList.sort(Comparator.naturalOrder());
            runList.sort(Comparator.naturalOrder());
            String canonical = canonicalize(
                    normalizedSummary, decisionList, questionList, fileList, runList);
            if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_SERIALIZED_BYTES) {
                throw new IllegalArgumentException(
                        "handoff content exceeds the 256 KiB limit");
            }
            return new HandoffContent(
                    normalizedSummary, decisionList, questionList, fileList, runList,
                    CanonicalHashing.sha256(canonical));
        }

        private static <T> List<T> copyAndLimit(
                List<T> values, int maximum, String name) {
            if (values == null || values.contains(null)) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
            if (values.size() > maximum) {
                throw new IllegalArgumentException(
                        name + " must contain at most " + maximum + " items");
            }
            return new ArrayList<T>(values);
        }

        private static void requirePinnedFilesInScope(
                List<DocumentReference> files, RepositoryScope scope) {
            Set<DocumentReference> unique = new HashSet<DocumentReference>();
            for (DocumentReference file : files) {
                if (!scope.containsRepository(file.getRepositoryKey())) {
                    throw new IllegalArgumentException(
                            "pinned file repository is outside the repository scope: "
                                    + file.getRepositoryKey());
                }
                if (!unique.add(file)) {
                    throw new IllegalArgumentException(
                            "pinned file references must not contain duplicates");
                }
            }
        }

        private static void requireRunsBelongToWorkbench(
                List<WorkbenchRunReference> runs, WorkbenchId workbenchId) {
            Set<String> unique = new HashSet<String>();
            for (WorkbenchRunReference run : runs) {
                if (!workbenchId.equals(run.getWorkbenchId())) {
                    throw new IllegalArgumentException(
                            "referenced run must belong to the same workbench");
                }
                if (!unique.add(run.getRunId())) {
                    throw new IllegalArgumentException(
                            "referenced runs must not contain duplicates");
                }
            }
        }

        private static String canonicalize(
                String summary, List<Decision> decisions,
                List<OpenQuestion> questions, List<DocumentReference> files,
                List<WorkbenchRunReference> runs) {
            StringBuilder canonical = new StringBuilder();
            CanonicalHashing.appendFramed(canonical, "schema", CONTENT_HASH_SCHEMA);
            CanonicalHashing.appendFramed(canonical, "summary", summary);
            for (Decision decision : decisions) {
                CanonicalHashing.appendFramed(canonical, "decisionText", decision.getText());
                CanonicalHashing.appendFramed(
                        canonical, "decisionRationale", decision.getRationale());
                CanonicalHashing.appendFramed(
                        canonical, "decisionStatus", decision.getStatus());
            }
            for (OpenQuestion question : questions) {
                CanonicalHashing.appendFramed(canonical, "questionText", question.getText());
                CanonicalHashing.appendFramed(
                        canonical, "questionOwner", question.getOwnerHint());
            }
            for (DocumentReference file : files) {
                CanonicalHashing.appendFramed(
                        canonical, "pinnedRepository", file.getRepositoryKey());
                CanonicalHashing.appendFramed(
                        canonical, "pinnedPath", file.getRelativePath());
            }
            for (WorkbenchRunReference run : runs) {
                CanonicalHashing.appendFramed(canonical, "runId", run.getRunId());
                CanonicalHashing.appendFramed(canonical, "runPhase", run.getPhase());
                CanonicalHashing.appendFramed(
                        canonical, "runSafeSummary", run.getSafeSummary());
            }
            return canonical.toString();
        }
    }
}
