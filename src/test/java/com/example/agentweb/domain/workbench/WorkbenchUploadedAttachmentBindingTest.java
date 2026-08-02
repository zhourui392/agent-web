package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workbench 聚合对上传附件 Owner、Phase 与会话代际授权的测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchUploadedAttachmentBindingTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");

    @Test
    void currentConversationShouldProduceExactUploadBinding() {
        Workbench workbench = workbench();
        workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "conversation-1", OWNER, NOW.plusSeconds(1));

        UploadedAttachmentBinding binding = workbench.planUploadedAttachment(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, 0, OWNER);

        assertEquals(OWNER, binding.getOwner());
        assertEquals(workbench.getId(), binding.getWorkbenchId());
        assertEquals(WorkbenchPhase.REQUIREMENT_ANALYSIS, binding.getPhase());
        assertEquals("conversation-1", binding.getConversationId());
        assertEquals(0, binding.getConversationGeneration());
    }

    @Test
    void missingStaleForeignOrArchivedConversationShouldFailClosed() {
        Workbench workbench = workbench();

        assertCode(WorkbenchErrorCode.CONVERSATION_CONFLICT,
                () -> workbench.planUploadedAttachment(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, 0, OWNER));

        workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "conversation-1", OWNER, NOW.plusSeconds(1));
        assertCode(WorkbenchErrorCode.VERSION_CONFLICT,
                () -> workbench.planUploadedAttachment(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, 1, OWNER));
        assertCode(WorkbenchErrorCode.OWNER_REQUIRED,
                () -> workbench.planUploadedAttachment(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, 0,
                        OwnerReference.of("other", "Other")));

        workbench.archive(OWNER, NOW.plusSeconds(2));
        assertCode(WorkbenchErrorCode.ARCHIVED,
                () -> workbench.planUploadedAttachment(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, 0, OWNER));
    }

    private Workbench workbench() {
        RepositoryScope scope = RepositoryScope.create(
                "/workspace",
                RepositorySelection.of("agent-web",
                        Collections.singletonList("agent-web")),
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('a'), false)), 8);
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace", RepositorySelection.of(
                        "agent-web", Collections.singletonList("agent-web")));
        return Workbench.create(
                WorkbenchId.of("workbench-upload-binding"), OWNER,
                "Upload", "Discuss an attachment", AgentType.CODEX,
                "local", scope,
                new WorkspaceSnapshotReference(
                        "snapshot-1", topology.getTopologyHash(),
                        repeat('b'), 1), NOW);
    }

    private static void assertCode(
            WorkbenchErrorCode code, org.junit.jupiter.api.function.Executable action) {
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class, action);
        assertEquals(code, failure.getCode());
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, String.valueOf(value)));
    }
}
