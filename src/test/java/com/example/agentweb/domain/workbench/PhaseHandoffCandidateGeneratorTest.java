package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.RepositoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Handoff Candidate 的公开消息边界、绑定事实与确定性生成策略测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseHandoffCandidateGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-08-01T16:00:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final WorkbenchPhase PHASE =
            WorkbenchPhase.SOLUTION_DESIGN;

    private final PhaseHandoffCandidateGenerator generator =
            new PhaseHandoffCandidateGenerator();

    @Test
    void shouldGenerateFiveCandidateFieldsAndBindCurrentConversationAndBaseVersion() {
        Workbench workbench = workbench();
        PhaseHandoff base = baseHandoff(workbench.getRepositoryScope());
        long versionBeforeGeneration = base.getVersion();
        String hashBeforeGeneration = base.getContentHash();
        HandoffCandidateConversation conversation =
                HandoffCandidateConversation.capture(
                        "conversation-1", 0, Arrays.asList(
                                HandoffCandidateMessage.publicMessage(
                                        1L, "user", "请总结当前方案", null),
                                HandoffCandidateMessage.publicMessage(
                                        2L, "assistant",
                                        "阶段结果\n"
                                                + "- Decision: 使用 DDD 分层\n"
                                                + "- 决定：保持公开 API 稳定\n"
                                                + "- Open Question: 谁负责灰度？\n"
                                                + "- 未决问题：回滚演练何时执行？\n"
                                                + "- Pinned File: agent-web::docs/design.md\n"
                                                + "- Pinned File: outside::secret.txt\n"
                                                + "- Pinned File: agent-web::../escape.txt",
                                        "run-design-1"),
                                HandoffCandidateMessage.publicMessage(
                                        3L, "assistant", "最终方案说明", "run-design-2")));

        PhaseHandoffCandidate candidate = generator.generate(
                OWNER, workbench, PHASE, Optional.of(base), conversation);

        assertEquals(OWNER, candidate.getOwner());
        assertEquals(WORKBENCH_ID, candidate.getWorkbenchId());
        assertEquals(PHASE, candidate.getSourcePhase());
        assertEquals("conversation-1", candidate.getConversationId());
        assertEquals(0, candidate.getConversationGeneration());
        assertEquals(1L, candidate.getBaseHandoffVersion());
        assertEquals(3, candidate.getSourceMessageCount());
        assertEquals(
                HandoffCandidateStrategy.DETERMINISTIC_PUBLIC_MESSAGES_V1,
                candidate.getStrategy());
        assertEquals("最终方案说明", candidate.getSummary());
        assertEquals(Arrays.asList(
                        "使用 DDD 分层", "保持公开 API 稳定"),
                Arrays.asList(
                        candidate.getDecisions().get(0).getText(),
                        candidate.getDecisions().get(1).getText()));
        assertEquals(Arrays.asList(
                        "谁负责灰度？", "回滚演练何时执行？"),
                Arrays.asList(
                        candidate.getOpenQuestions().get(0).getText(),
                        candidate.getOpenQuestions().get(1).getText()));
        assertEquals(Collections.singletonList(
                        DocumentReference.of("agent-web", "docs/design.md")),
                candidate.getPinnedFiles());
        assertEquals(Arrays.asList("run-design-1", "run-design-2"),
                Arrays.asList(
                        candidate.getReferencedRuns().get(0).getRunId(),
                        candidate.getReferencedRuns().get(1).getRunId()));
        assertEquals(PHASE,
                candidate.getReferencedRuns().get(0).getPhase());
        assertEquals("Run run-design-1 (SOLUTION_DESIGN)",
                candidate.getReferencedRuns().get(0).getSafeSummary());

        assertEquals(versionBeforeGeneration, base.getVersion());
        assertEquals(hashBeforeGeneration, base.getContentHash());
        assertThrows(UnsupportedOperationException.class,
                () -> candidate.getDecisions().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> candidate.getReferencedRuns().clear());
    }

    @Test
    void shouldUseZeroBaseVersionAndEmptyFiveFieldsWhenNoManualHandoffOrAgentReplyExists() {
        Workbench workbench = workbench();
        HandoffCandidateConversation conversation =
                HandoffCandidateConversation.capture(
                        "conversation-1", 0,
                        Collections.singletonList(
                                HandoffCandidateMessage.publicMessage(
                                        1L, "user", "尚未回复", null)));

        PhaseHandoffCandidate candidate = generator.generate(
                OWNER, workbench, PHASE,
                Optional.<PhaseHandoff>empty(), conversation);

        assertEquals(0L, candidate.getBaseHandoffVersion());
        assertEquals("", candidate.getSummary());
        assertEquals(Collections.emptyList(), candidate.getDecisions());
        assertEquals(Collections.emptyList(), candidate.getOpenQuestions());
        assertEquals(Collections.emptyList(), candidate.getPinnedFiles());
        assertEquals(Collections.emptyList(), candidate.getReferencedRuns());
    }

    @Test
    void shouldRejectNonPublicToolMessageBeforeCandidateGeneration() {
        assertThrows(IllegalArgumentException.class,
                () -> HandoffCandidateMessage.publicMessage(
                        1L, "tool", "raw tool output", "run-1"));
    }

    @Test
    void shouldRejectConversationGenerationRaceAndForeignBaseHandoff() {
        Workbench workbench = workbench();
        HandoffCandidateConversation restartedConversation =
                HandoffCandidateConversation.capture(
                        "conversation-2", 1,
                        Collections.<HandoffCandidateMessage>emptyList());

        WorkbenchDomainException generationFailure = assertThrows(
                WorkbenchDomainException.class,
                () -> generator.generate(
                        OWNER, workbench, PHASE,
                        Optional.<PhaseHandoff>empty(), restartedConversation));

        assertEquals(WorkbenchErrorCode.CONVERSATION_CONFLICT,
                generationFailure.getCode());

        PhaseHandoff foreignPhase = PhaseHandoff.create(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "manual", Collections.<Decision>emptyList(),
                Collections.<OpenQuestion>emptyList(),
                Collections.<DocumentReference>emptyList(),
                Collections.<WorkbenchRunReference>emptyList(),
                workbench.getRepositoryScope(), OWNER, NOW);
        HandoffCandidateConversation currentConversation =
                HandoffCandidateConversation.capture(
                        "conversation-1", 0,
                        Collections.<HandoffCandidateMessage>emptyList());

        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(
                        OWNER, workbench, PHASE,
                        Optional.of(foreignPhase), currentConversation));
    }

    private static Workbench workbench() {
        RepositoryScope scope = WorkbenchDomainFixtures.repositoryScope();
        Workbench workbench = Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "实现工作台",
                AgentType.CODEX, "test", scope,
                WorkbenchDomainFixtures.snapshotReference(
                        "snapshot-1", WorkbenchDomainFixtures.repeat('a')),
                NOW);
        workbench.bindConversation(
                PHASE, "conversation-1", OWNER, NOW.plusSeconds(1));
        return workbench;
    }

    private static PhaseHandoff baseHandoff(RepositoryScope scope) {
        PhaseHandoff handoff = PhaseHandoff.create(
                WORKBENCH_ID, PHASE, "manual",
                Collections.<Decision>emptyList(),
                Collections.<OpenQuestion>emptyList(),
                Collections.<DocumentReference>emptyList(),
                Collections.<WorkbenchRunReference>emptyList(),
                scope, OWNER, NOW);
        handoff.update(
                0L, "manual-v2", Collections.<Decision>emptyList(),
                Collections.<OpenQuestion>emptyList(),
                Collections.<DocumentReference>emptyList(),
                Collections.<WorkbenchRunReference>emptyList(),
                scope, OWNER, NOW.plusSeconds(1));
        return handoff;
    }
}
