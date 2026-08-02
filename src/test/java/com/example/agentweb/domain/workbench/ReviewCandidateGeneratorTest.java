package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workspace.RepositoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 结构化 Review Candidate、会话绑定与人工 Opinion 确认边界测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ReviewCandidateGeneratorTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T17:00:00Z");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");

    private final ReviewCandidateGenerator generator =
            new ReviewCandidateGenerator();

    @Test
    void shouldGenerateStructuredItemsBoundToCurrentReviewConversationAndOpinion() {
        Workbench workbench = workbench();
        ReviewOpinion baseOpinion = opinion(2L, "人工既有意见");
        ReviewCandidateConversation conversation =
                ReviewCandidateConversation.capture(
                        "review-conversation-1", 0, Arrays.asList(
                                ReviewCandidateMessage.publicMessage(
                                        1L, "user", "请解释 Review 意见"),
                                ReviewCandidateMessage.publicMessage(
                                        2L, "assistant",
                                        "- Review: Application 层代替聚合判断缓存状态\n"
                                                + "  Impact: 新状态会继续扩大条件分支\n"
                                                + "  Suggested Change: 将语义查询下沉聚合根\n"
                                                + "  Affected File: agent-web::src/main/java/A.java\n"
                                                + "  Affected File: outside::secret.java\n"
                                                + "  Required Test: 运行 A 聚合单测\n"
                                                + "- 审查意见：Controller 错误合同缺少覆盖\n"
                                                + "  影响：客户端无法区分并发冲突\n"
                                                + "  重构方案：补稳定 409 合同\n"
                                                + "  影响文件：agent-web::src/test/java/AControllerTest.java\n"
                                                + "  受影响测试：运行 Controller 聚焦测试")));

        ReviewCandidate candidate = generator.generate(
                OWNER, workbench, Optional.of(baseOpinion), conversation);

        assertEquals(OWNER, candidate.getOwner());
        assertEquals(WORKBENCH_ID, candidate.getWorkbenchId());
        assertEquals(WorkbenchPhase.REVIEW_REFACTOR, candidate.getPhase());
        assertEquals("review-conversation-1", candidate.getConversationId());
        assertEquals(0, candidate.getConversationGeneration());
        assertEquals(2L, candidate.getBaseOpinionVersion());
        assertEquals(2, candidate.getSourceMessageCount());
        assertEquals(ReviewCandidateStrategy.DETERMINISTIC_PUBLIC_REVIEW_MESSAGES_V1,
                candidate.getStrategy());
        assertEquals(2, candidate.getItems().size());

        ReviewCandidateItem first = candidate.getItems().get(0);
        assertEquals("Application 层代替聚合判断缓存状态",
                first.getFinding());
        assertEquals("新状态会继续扩大条件分支", first.getImpact());
        assertEquals("将语义查询下沉聚合根",
                first.getSuggestedChange());
        assertEquals(Collections.singletonList(
                        DocumentReference.of(
                                "agent-web", "src/main/java/A.java")),
                first.getAffectedFiles());
        assertEquals(Collections.singletonList("运行 A 聚合单测"),
                first.getSuggestedTests());
        assertEquals(64, first.getItemId().length());

        ReviewCandidateItem second = candidate.getItems().get(1);
        assertNotEquals(first.getItemId(), second.getItemId());
        assertEquals("Controller 错误合同缺少覆盖", second.getFinding());
        assertEquals("补稳定 409 合同", second.getSuggestedChange());
        assertEquals(Collections.singletonList("运行 Controller 聚焦测试"),
                second.getSuggestedTests());
        assertThrows(UnsupportedOperationException.class,
                () -> candidate.getItems().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> first.getSuggestedTests().clear());
        assertEquals(2L, baseOpinion.getVersion());
        assertEquals("人工既有意见", baseOpinion.getContent());
    }

    @Test
    void shouldFallBackToLatestAssistantReplyWithoutInventingStructuredDetails() {
        Workbench workbench = workbench();
        ReviewCandidateConversation conversation =
                ReviewCandidateConversation.capture(
                        "review-conversation-1", 0, Arrays.asList(
                                ReviewCandidateMessage.publicMessage(
                                        1L, "assistant", "较早的解释"),
                                ReviewCandidateMessage.publicMessage(
                                        2L, "assistant", "建议缩小事务边界")));

        ReviewCandidate candidate = generator.generate(
                OWNER, workbench, Optional.<ReviewOpinion>empty(),
                conversation);

        assertEquals(0L, candidate.getBaseOpinionVersion());
        assertEquals(1, candidate.getItems().size());
        assertEquals("建议缩小事务边界",
                candidate.getItems().get(0).getFinding());
        assertEquals("", candidate.getItems().get(0).getImpact());
        assertEquals("", candidate.getItems().get(0).getSuggestedChange());
        assertEquals(Collections.emptyList(),
                candidate.getItems().get(0).getAffectedFiles());
        assertEquals(Collections.emptyList(),
                candidate.getItems().get(0).getSuggestedTests());
    }

    @Test
    void shouldRejectToolOutputAndConversationRestartRace() {
        assertThrows(IllegalArgumentException.class,
                () -> ReviewCandidateMessage.publicMessage(
                        1L, "tool", "raw tool output"));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> generator.generate(
                        OWNER, workbench(), Optional.<ReviewOpinion>empty(),
                        ReviewCandidateConversation.capture(
                                "review-conversation-2", 1,
                                Collections.<ReviewCandidateMessage>emptyList())));

        assertEquals(WorkbenchErrorCode.CONVERSATION_CONFLICT,
                failure.getCode());
    }

    @Test
    void shouldOmitPhysicalAbsolutePathsFromCandidateText() {
        Workbench workbench = workbench();
        String physicalFile = workbench.getRepositoryScope()
                .primaryRepository().getRepositoryRoot() + "/Secret.java";
        ReviewCandidate candidate = generator.generate(
                OWNER, workbench, Optional.<ReviewOpinion>empty(),
                ReviewCandidateConversation.capture(
                        "review-conversation-1", 0,
                        Collections.singletonList(
                                ReviewCandidateMessage.publicMessage(
                                        1L, "assistant",
                                        "Review: " + physicalFile
                                                + " 存在问题\n"
                                                + "Impact: 不应返回物理路径"))));

        assertEquals(Collections.emptyList(), candidate.getItems());
    }

    @Test
    void candidateCannotAuthorizeModifyUntilHumanSavesAndConfirmsExactOpinion() {
        Workbench workbench = workbench();
        ReviewCandidate candidate = generator.generate(
                OWNER, workbench, Optional.<ReviewOpinion>empty(),
                ReviewCandidateConversation.capture(
                        "review-conversation-1", 0,
                        Collections.singletonList(
                                ReviewCandidateMessage.publicMessage(
                                        1L, "assistant",
                                        "Review: 拆分过大的服务\n"
                                                + "Suggested Change: 只提取领域策略\n"
                                                + "Required Test: 运行受影响单测"))));

        assertDoesNotThrow(() -> PhaseRunPolicy.requireAllowed(
                WorkbenchPhase.REVIEW_REFACTOR,
                RunMode.MODIFY_WORKSPACE, null));

        ReviewCandidateItem accepted = candidate.getItems().get(0);
        String humanModifiedOpinion = accepted.getFinding()
                + "；仅执行：" + accepted.getSuggestedChange()
                + "；并且：" + accepted.getSuggestedTests().get(0);
        ReviewOpinion opinion = ReviewOpinion.start(
                WORKBENCH_ID, 0L, humanModifiedOpinion, OWNER, NOW);
        ReviewModifyConfirmation confirmation = opinion.confirmModify(
                "confirmation-1", opinion.getVersion(),
                opinion.getContentHash(), OWNER, NOW.plusSeconds(1));

        assertDoesNotThrow(() -> PhaseRunPolicy.requireAllowed(
                WorkbenchPhase.REVIEW_REFACTOR,
                RunMode.MODIFY_WORKSPACE, confirmation));
        assertDoesNotThrow(() -> opinion.requireExactContent(
                humanModifiedOpinion));
        WorkbenchDomainException changedAfterConfirmation = assertThrows(
                WorkbenchDomainException.class,
                () -> opinion.requireExactContent(
                        humanModifiedOpinion + "；扩大重构范围"));
        assertEquals(WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                changedAfterConfirmation.getCode());
    }

    private static ReviewOpinion opinion(long version, String content) {
        return ReviewOpinion.restore(
                WORKBENCH_ID, version, content,
                CanonicalHashing.sha256(content), OWNER,
                NOW.minusSeconds(1));
    }

    private static Workbench workbench() {
        RepositoryScope scope = WorkbenchDomainFixtures.repositoryScope();
        Workbench workbench = Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "Review code",
                AgentType.CODEX, "test", scope,
                WorkbenchDomainFixtures.snapshotReference(
                        "snapshot-review", WorkbenchDomainFixtures.repeat('c')),
                NOW.minusSeconds(2));
        workbench.bindConversation(
                WorkbenchPhase.REVIEW_REFACTOR,
                "review-conversation-1", OWNER, NOW.minusSeconds(1));
        return workbench;
    }
}
