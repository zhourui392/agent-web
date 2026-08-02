package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentStatus;
import com.example.agentweb.domain.workbench.VerifiedUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 浏览器上传附件聚合的真实 SQLite 生命周期与 Owner 完整性测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteUploadedConversationAttachmentRepositoryTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T00:00:00Z");
    private static final UploadedAttachmentPolicy POLICY =
            UploadedAttachmentPolicy.standard(
                    1024L, 16, Duration.ofHours(24), Duration.ofHours(2));

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteUploadedConversationAttachmentRepository repository;
    private Workbench workbench;
    private UploadedAttachmentBinding binding;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("uploaded-attachment.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "uploaded-attachment-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-uploaded-attachment");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        binding = new UploadedAttachmentBinding(
                WorkbenchPersistenceFixtures.OWNER, workbench.getId(),
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "conversation-1", 0);
        repository = new SqliteUploadedConversationAttachmentRepository(jdbc);
    }

    @Test
    void shouldRoundTripBindReleaseAndCleanupWithOptimisticVersions() {
        UploadedConversationAttachment attachment = attachment();
        repository.add(attachment);

        UploadedConversationAttachment available = repository
                .findById("attachment-1")
                .orElseThrow(AssertionError::new);
        assertEquals(UploadedConversationAttachmentStatus.AVAILABLE,
                available.getStatus());
        assertEquals(1L, repository.countAvailable(
                binding, NOW.plusSeconds(1)));
        VerifiedUploadedConversationAttachment verified =
                available.verifyForRun(
                        binding, WorkbenchPersistenceFixtures.HASH_A,
                        NOW.plusSeconds(1));
        available.bindToRun(
                verified, "run-1", NOW.plusSeconds(2), POLICY);
        repository.update(available, 0L);

        UploadedConversationAttachment bound = repository
                .findById("attachment-1")
                .orElseThrow(AssertionError::new);
        assertEquals(UploadedConversationAttachmentStatus.BOUND,
                bound.getStatus());
        assertEquals("run-1", bound.getBoundRunId());
        assertEquals(0L, repository.countAvailable(
                binding, NOW.plusSeconds(3)));
        bound.releaseAfterTerminal("run-1", NOW.plusSeconds(4));
        repository.update(bound, 1L);

        assertEquals(1, repository.findCleanupCandidates(
                NOW.plusSeconds(4), 10).size());
        repository.delete(bound);
        assertFalse(repository.findById("attachment-1").isPresent());
    }

    @Test
    void ownerCorruptionShouldFailClosedAndSchemaMustNotStorePathsOrSecrets() {
        repository.add(attachment());
        jdbc.update("UPDATE workbench SET owner_id='corrupt-owner', "
                + "owner_name='Corrupt' WHERE id=?",
                workbench.getId().getValue());

        assertThrows(IllegalStateException.class,
                () -> repository.findById("attachment-1"));

        List<Map<String, Object>> columns = jdbc.queryForList(
                "PRAGMA table_info(workbench_uploaded_attachment)");
        for (Map<String, Object> column : columns) {
            String name = String.valueOf(column.get("name")).toLowerCase();
            assertFalse(name.contains("path"));
            assertFalse(name.contains("secret"));
            assertFalse(name.contains("token"));
        }
    }

    private UploadedConversationAttachment attachment() {
        return UploadedConversationAttachment.upload(
                "attachment-1", binding, "design.md", "text/markdown",
                UploadedAttachmentContentSignature.TEXT, 64L,
                WorkbenchPersistenceFixtures.HASH_A,
                WorkbenchPersistenceFixtures.HASH_B,
                POLICY, NOW);
    }
}
