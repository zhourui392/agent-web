package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.workspace.RepositoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工 Handoff 内容、引用归属、Hash 与版本接收测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseHandoffTest {

    private static final Instant NOW = Instant.parse("2026-08-01T01:00:00Z");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");

    @Test
    void createShouldKeepFiveHumanEditableFieldsAndStableContentHash() {
        PhaseHandoff first = newHandoff(
                Arrays.asList(
                        DocumentReference.of("shared-library", "src/A.java"),
                        DocumentReference.of("agent-web", "README.md")),
                Arrays.asList(
                        WorkbenchRunReference.of(
                                "run-2", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                "测试结果"),
                        WorkbenchRunReference.of(
                                "run-1", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                "需求分析")));
        PhaseHandoff reordered = newHandoff(
                Arrays.asList(
                        DocumentReference.of("agent-web", "README.md"),
                        DocumentReference.of("shared-library", "src/A.java")),
                Arrays.asList(
                        WorkbenchRunReference.of(
                                "run-1", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                "需求分析"),
                        WorkbenchRunReference.of(
                                "run-2", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                "测试结果")));

        assertEquals("范围与验收标准已确认", first.getSummary());
        assertEquals(1, first.getDecisions().size());
        assertEquals(1, first.getOpenQuestions().size());
        assertEquals(2, first.getPinnedFiles().size());
        assertEquals(2, first.getReferencedRuns().size());
        assertEquals(first.getContentHash(), reordered.getContentHash());
        assertTrue(first.getContentHash().matches("[a-f0-9]{64}"));
        assertEquals(0L, first.getVersion());
        assertThrows(UnsupportedOperationException.class,
                () -> first.getDecisions().add(Decision.confirmed("新增决定", null)));
    }

    @Test
    void updateShouldUseExpectedVersionAndNeverApplyCandidateImplicitly() {
        PhaseHandoff handoff = newHandoff(
                Collections.singletonList(DocumentReference.of("agent-web", "README.md")),
                Collections.singletonList(WorkbenchRunReference.of(
                        "run-1", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "需求分析")));
        String initialHash = handoff.getContentHash();

        handoff.update(
                0L, "方案取舍已更新",
                Collections.singletonList(Decision.confirmed("使用独立 Workbench", "避免 Harness 耦合")),
                Collections.<OpenQuestion>emptyList(),
                Collections.singletonList(DocumentReference.of("agent-web", "docs/design.md")),
                Collections.singletonList(WorkbenchRunReference.of(
                        "run-2", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "方案讨论")),
                scope(), OWNER, NOW.plusSeconds(1));

        assertEquals(1L, handoff.getVersion());
        assertEquals("方案取舍已更新", handoff.getSummary());
        assertNotEquals(initialHash, handoff.getContentHash());
        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class,
                () -> handoff.update(
                        0L, "stale write", Collections.<Decision>emptyList(),
                        Collections.<OpenQuestion>emptyList(),
                        Collections.<DocumentReference>emptyList(),
                        Collections.<WorkbenchRunReference>emptyList(),
                        scope(), OWNER, NOW.plusSeconds(2)));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, conflict.getCode());
        assertEquals("方案取舍已更新", handoff.getSummary());
    }

    @Test
    void revisionShouldCaptureAnImmutableExactVersionInsteadOfTheMutableLatestAggregate() {
        PhaseHandoff handoff = newHandoff(
                Collections.singletonList(DocumentReference.of("agent-web", "README.md")),
                Collections.singletonList(WorkbenchRunReference.of(
                        "run-1", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "需求分析")));
        PhaseHandoffRevision revisionZero = PhaseHandoffRevision.capture(handoff);
        String revisionZeroHash = revisionZero.getContentHash();

        handoff.update(
                0L, "方案取舍已更新",
                Collections.singletonList(
                        Decision.confirmed("使用独立 Workbench", "避免 Harness 耦合")),
                Collections.<OpenQuestion>emptyList(),
                Collections.singletonList(
                        DocumentReference.of("agent-web", "docs/design.md")),
                Collections.singletonList(WorkbenchRunReference.of(
                        "run-2", WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, "方案讨论")),
                scope(), OWNER, NOW.plusSeconds(1));
        PhaseHandoffRevision revisionOne = PhaseHandoffRevision.capture(handoff);

        assertEquals(0L, revisionZero.getVersion());
        assertEquals("范围与验收标准已确认", revisionZero.getSummary());
        assertEquals(revisionZeroHash, revisionZero.getContentHash());
        assertEquals(1L, revisionOne.getVersion());
        assertEquals("方案取舍已更新", revisionOne.getSummary());
        assertNotEquals(revisionZero.getContentHash(), revisionOne.getContentHash());
        assertThrows(UnsupportedOperationException.class,
                () -> revisionZero.getDecisions().add(
                        Decision.confirmed("不能篡改历史", null)));
    }

    @Test
    void revisionRestoreShouldRevalidateIdentityContentVersionAndHash() {
        PhaseHandoffRevision captured = PhaseHandoffRevision.capture(newHandoff(
                Collections.singletonList(
                        DocumentReference.of("agent-web", "README.md")),
                Collections.singletonList(WorkbenchRunReference.of(
                        "run-1", WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, "需求分析"))));

        PhaseHandoffRevision restored = restoreRevision(
                captured, WORKBENCH_ID, captured.getVersion(), captured.getContentHash());

        assertEquals(captured.getWorkbenchId(), restored.getWorkbenchId());
        assertEquals(captured.getSourcePhase(), restored.getSourcePhase());
        assertEquals(captured.getSummary(), restored.getSummary());
        assertEquals(captured.getDecisions(), restored.getDecisions());
        assertEquals(captured.getOpenQuestions(), restored.getOpenQuestions());
        assertEquals(captured.getPinnedFiles(), restored.getPinnedFiles());
        assertEquals(captured.getReferencedRuns(), restored.getReferencedRuns());
        assertEquals(captured.getContentHash(), restored.getContentHash());
        assertEquals(captured.getUpdatedBy(), restored.getUpdatedBy());
        assertEquals(captured.getUpdatedAt(), restored.getUpdatedAt());
        assertEquals(captured.getVersion(), restored.getVersion());

        assertThrows(IllegalArgumentException.class,
                () -> restoreRevision(
                        captured, null, captured.getVersion(), captured.getContentHash()));
        assertThrows(IllegalArgumentException.class,
                () -> restoreRevision(
                        captured, WORKBENCH_ID, -1L, captured.getContentHash()));
        assertThrows(IllegalArgumentException.class,
                () -> restoreRevision(
                        captured, WORKBENCH_ID, captured.getVersion(), repeat('f')));
    }

    @Test
    void handoffShouldRejectOutOfScopeDuplicateAndForeignReferences() {
        assertThrows(IllegalArgumentException.class,
                () -> newHandoff(
                        Collections.singletonList(
                                DocumentReference.of("unselected", "README.md")),
                        Collections.<WorkbenchRunReference>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> newHandoff(
                        Arrays.asList(
                                DocumentReference.of("agent-web", "README.md"),
                                DocumentReference.of("agent-web", "README.md")),
                        Collections.<WorkbenchRunReference>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> newHandoff(
                        Collections.<DocumentReference>emptyList(),
                        Collections.singletonList(WorkbenchRunReference.of(
                                "run-foreign", WorkbenchId.of("workbench-2"),
                                WorkbenchPhase.REQUIREMENT_ANALYSIS, "foreign"))));
    }

    @Test
    void documentReferenceShouldRejectAbsoluteAndTraversalPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> DocumentReference.of("agent-web", "/etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> DocumentReference.of("agent-web", "docs/../data/secrets.properties"));
        assertThrows(IllegalArgumentException.class,
                () -> DocumentReference.of("agent-web", "C:\\secret.txt"));
    }

    @Test
    void receptionShouldBindDefaultUpstreamVersionWithoutAutomaticReplacement() {
        HandoffReception reception = HandoffReception.accept(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, 2L, repeat('d'),
                OWNER, NOW);

        assertFalse(reception.isStale(2L, repeat('d')));
        assertTrue(reception.isStale(3L, repeat('e')));
        assertEquals(2L, reception.getSourceVersion());
        assertThrows(IllegalArgumentException.class,
                () -> HandoffReception.accept(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, 2L, repeat('d'),
                        OWNER, NOW));
    }

    @Test
    void receptionShouldAllowInitialHandoffVersionZero() {
        HandoffReception reception = HandoffReception.accept(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, 0L, repeat('d'),
                OWNER, NOW);

        assertEquals(0L, reception.getSourceVersion());
        assertFalse(reception.isStale(0L, repeat('d')));
        assertDoesNotThrow(() -> reception.requireLatest(0L, repeat('d')));
    }

    @Test
    void handoffShouldRejectCommonSecretLikeMaterialWithoutEchoingIt() {
        String[] secretLikeValues = {
                "api_key=sk-live-Abcdef1234567890",
                "token: ghp_1234567890abcdefghijklmnop",
                "Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456",
                "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkq",
                "password = correct-horse-battery-staple"
        };

        for (String secretLikeValue : secretLikeValues) {
            WorkbenchDomainException failure = assertThrows(
                    WorkbenchDomainException.class,
                    () -> handoffWithContent(
                            secretLikeValue,
                            Collections.singletonList(Decision.confirmed(
                                    "显式选择仓库", "避免 sibling 越权")),
                            Collections.singletonList(OpenQuestion.of(
                                    "首个试点是否只支持 Codex", "owner")),
                            Collections.singletonList(
                                    WorkbenchRunReference.of(
                                            "run-1", WORKBENCH_ID,
                                            WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                            "需求分析"))));

            assertEquals(WorkbenchErrorCode.HANDOFF_SECRET_DETECTED,
                    failure.getCode());
            assertFalse(failure.getMessage().contains(secretLikeValue));
        }
    }

    @Test
    void handoffShouldInspectEveryHumanTextFieldButAllowPolicyDescriptions() {
        assertSecretRejected(() -> handoffWithContent(
                "安全交接",
                Collections.singletonList(Decision.confirmed(
                        "token=abcdefghijklmnop", "正常理由")),
                Collections.<OpenQuestion>emptyList(),
                Collections.<WorkbenchRunReference>emptyList()));
        assertSecretRejected(() -> handoffWithContent(
                "安全交接",
                Collections.singletonList(Decision.confirmed(
                        "正常决定", "password: hunter2-secret")),
                Collections.<OpenQuestion>emptyList(),
                Collections.<WorkbenchRunReference>emptyList()));
        assertSecretRejected(() -> handoffWithContent(
                "安全交接", Collections.<Decision>emptyList(),
                Collections.singletonList(OpenQuestion.of(
                        "apiKey=Abcdef1234567890", "owner")),
                Collections.<WorkbenchRunReference>emptyList()));
        assertSecretRejected(() -> handoffWithContent(
                "安全交接", Collections.<Decision>emptyList(),
                Collections.singletonList(OpenQuestion.of(
                        "正常问题", "token=abcdefghijklmnop")),
                Collections.<WorkbenchRunReference>emptyList()));
        assertSecretRejected(() -> handoffWithContent(
                "安全交接", Collections.<Decision>emptyList(),
                Collections.<OpenQuestion>emptyList(),
                Collections.singletonList(WorkbenchRunReference.of(
                        "run-1", WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "api_key=Abcdef1234567890"))));

        assertDoesNotThrow(() -> handoffWithContent(
                "Password policy requires 16 characters and MFA; "
                        + "API key management uses the secret store.",
                Collections.singletonList(Decision.confirmed(
                        "Rotate private-key material through the approved process",
                        "Token bucket controls API rate limits")),
                Collections.singletonList(OpenQuestion.of(
                        "Should the password policy require MFA?", "security owner")),
                Collections.<WorkbenchRunReference>emptyList()));
    }

    @Test
    void receptionShouldOwnLatestVersionAndHashConflictSemantics() {
        HandoffReception reception = HandoffReception.accept(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, 2L, repeat('d'),
                OWNER, NOW);

        assertDoesNotThrow(() -> reception.requireLatest(2L, repeat('d')));
        WorkbenchDomainException versionConflict = assertThrows(
                WorkbenchDomainException.class,
                () -> reception.requireLatest(3L, repeat('d')));
        WorkbenchDomainException hashConflict = assertThrows(
                WorkbenchDomainException.class,
                () -> reception.requireLatest(2L, repeat('e')));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, versionConflict.getCode());
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, hashConflict.getCode());
    }

    private static PhaseHandoff newHandoff(
            java.util.List<DocumentReference> pinnedFiles,
            java.util.List<WorkbenchRunReference> referencedRuns) {
        return PhaseHandoff.create(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "范围与验收标准已确认",
                Collections.singletonList(
                        Decision.confirmed("显式选择仓库", "避免 sibling 越权")),
                Collections.singletonList(OpenQuestion.of("首个试点是否只支持 Codex", "owner")),
                pinnedFiles, referencedRuns, scope(), OWNER, NOW);
    }

    private static PhaseHandoff handoffWithContent(
            String summary, java.util.List<Decision> decisions,
            java.util.List<OpenQuestion> openQuestions,
            java.util.List<WorkbenchRunReference> referencedRuns) {
        return PhaseHandoff.create(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                summary, decisions, openQuestions,
                Collections.<DocumentReference>emptyList(), referencedRuns,
                scope(), OWNER, NOW);
    }

    private static void assertSecretRejected(Runnable operation) {
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class, operation::run);
        assertEquals(WorkbenchErrorCode.HANDOFF_SECRET_DETECTED,
                failure.getCode());
        assertEquals("handoff content contains secret-like material",
                failure.getMessage());
    }

    private static RepositoryScope scope() {
        return WorkbenchDomainFixtures.repositoryScope();
    }

    private static PhaseHandoffRevision restoreRevision(
            PhaseHandoffRevision captured, WorkbenchId workbenchId,
            long version, String contentHash) {
        return PhaseHandoffRevision.restore(
                workbenchId, captured.getSourcePhase(), captured.getSummary(),
                captured.getDecisions(), captured.getOpenQuestions(),
                captured.getPinnedFiles(), captured.getReferencedRuns(),
                contentHash, captured.getUpdatedBy(), captured.getUpdatedAt(),
                version, scope());
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
