package com.example.agentweb.infra.mode;

import com.example.agentweb.domain.mode.ChatMode;
import com.example.agentweb.domain.mode.DuplicateHandoffException;
import com.example.agentweb.domain.mode.HandoffDocument;
import com.example.agentweb.domain.mode.ModeCapabilities;
import com.example.agentweb.domain.mode.ModeNotFoundException;
import com.example.agentweb.domain.mode.ModePermissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatMode / HandoffDocument SQLite 仓储回归（真实 SQLite）。
 *
 * @author alex
 * @since 2026-08-20
 */
class SqliteChatModeRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteChatModeRepository repo;
    private SqliteHandoffDocumentRepository handoffRepo;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("mode-test.db").toAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE chat_mode (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, "
                + "identifier TEXT NOT NULL, display_name TEXT NOT NULL, description TEXT, "
                + "default_prompt TEXT, permission_mode TEXT, model TEXT, effort TEXT, "
                + "source_revision_id INTEGER, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, "
                + "version INTEGER NOT NULL DEFAULT 0, UNIQUE (user_id, identifier))");
        jdbc.execute("CREATE TABLE chat_mode_command (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "mode_id TEXT NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL, "
                + "sort_order INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE (mode_id, capability_identifier))");
        jdbc.execute("CREATE TABLE chat_mode_skill (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "mode_id TEXT NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL, "
                + "sort_order INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE (mode_id, capability_identifier))");
        jdbc.execute("CREATE TABLE chat_mode_mcp_server (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "mode_id TEXT NOT NULL, capability_identifier TEXT NOT NULL, "
                + "capability_version TEXT NOT NULL, capability_hash TEXT NOT NULL, "
                + "maximum_access TEXT NOT NULL, transport TEXT NOT NULL, "
                + "sort_order INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE (mode_id, capability_identifier))");
        jdbc.execute("CREATE TABLE handoff_document (id TEXT PRIMARY KEY, "
                + "from_session_id TEXT NOT NULL, to_session_id TEXT NOT NULL, "
                + "from_mode_id TEXT, to_mode_id TEXT, file_path TEXT NOT NULL, "
                + "idempotency_key TEXT NOT NULL, created_at TEXT NOT NULL, "
                + "UNIQUE (from_session_id, idempotency_key))");
        repo = new SqliteChatModeRepository(jdbc);
        handoffRepo = new SqliteHandoffDocumentRepository(jdbc);
    }

    private static String hash(String content) {
        return com.example.agentweb.domain.shared.CanonicalHashing.sha256(content);
    }

    @Test
    void saveAndFindRoundTripsCapabilities() {
        ModeCapabilities capabilities = new ModeCapabilities(
                List.of(new ModeCapabilities.CommandRef("cmd", "1", hash("cmd"), 0)),
                List.of(new ModeCapabilities.SkillRef("skill", "2", hash("skill"), 1)),
                List.of(new ModeCapabilities.McpServerRef("mcp", "3", hash("mcp"),
                        "WRITE", "STDIO", 2)));
        ChatMode mode = ChatMode.create("m1", "u1", "reviewer", "评审",
                "描述", "提示词", ModePermissionMode.PLAN, "claude-x", "high", null,
                capabilities, Instant.now());
        repo.save(mode);

        ChatMode loaded = repo.find("m1").orElseThrow();
        assertEquals("reviewer", loaded.getIdentifier());
        assertEquals(ModePermissionMode.PLAN, loaded.getPermissionMode());
        assertEquals("claude-x", loaded.getModel());
        assertEquals("high", loaded.getEffort());
        assertEquals(0, loaded.getVersion());
        assertEquals(1, loaded.getCapabilities().getCommands().size());
        assertEquals(1, loaded.getCapabilities().getSkills().size());
        assertEquals("WRITE", loaded.getCapabilities().getMcpServers().get(0).getMaximumAccess());
    }

    @Test
    void saveAndFindRoundTripsNonNullSourceRevisionId() {
        ChatMode mode = ChatMode.create("m-fork", "u1", "fork-reviewer", "fork 评审",
                "描述", "提示词", null, null, null, 7L,
                ModeCapabilities.empty(), Instant.now());
        repo.save(mode);

        ChatMode loaded = repo.find("m-fork").orElseThrow();
        assertEquals(7L, loaded.getSourceRevisionId());
    }

    @Test
    void editThenSaveBumpsPersistedVersion() {
        ChatMode mode = ChatMode.create("m1", "u1", "reviewer", "评审",
                null, null, null, null, null, null,
                ModeCapabilities.empty(), Instant.now());
        repo.save(mode);
        mode.edit("改名", null, null, null, null, null, null, Instant.now());
        repo.save(mode);
        assertEquals(1, repo.find("m1").orElseThrow().getVersion());
        assertEquals("改名", repo.find("m1").orElseThrow().getDisplayName());
    }

    @Test
    void duplicateIdentifierPerUserIsConflict() {
        ChatMode first = ChatMode.create("m1", "u1", "reviewer", "评审",
                null, null, null, null, null, null,
                ModeCapabilities.empty(), Instant.now());
        repo.save(first);
        ChatMode second = ChatMode.create("m2", "u1", "reviewer", "重复",
                null, null, null, null, null, null,
                ModeCapabilities.empty(), Instant.now());
        assertThrows(SqliteChatModeRepository.ModeWriteConflictException.class,
                () -> repo.save(second));
        // 同 identifier 不同用户允许
        ChatMode otherUser = ChatMode.create("m3", "u2", "reviewer", "他人评审",
                null, null, null, null, null, null,
                ModeCapabilities.empty(), Instant.now());
        repo.save(otherUser);
        assertTrue(repo.find("m3").isPresent());
    }

    @Test
    void deleteHonoursVersion() {
        ChatMode mode = ChatMode.create("m1", "u1", "reviewer", "评审",
                null, null, null, null, null, null,
                ModeCapabilities.empty(), Instant.now());
        repo.save(mode);
        repo.delete("m1", 0);
        assertTrue(repo.find("m1").isEmpty());
        ChatMode again = ChatMode.create("m2", "u1", "reviewer", "评审",
                null, null, null, null, null, null,
                ModeCapabilities.empty(), Instant.now());
        repo.save(again);
        // 存在但版本不符 → 并发冲突；不存在 → 统一按不存在语义
        assertThrows(SqliteChatModeRepository.ModeWriteConflictException.class,
                () -> repo.delete("m2", 99));
        assertThrows(ModeNotFoundException.class, () -> repo.delete("no-such-mode", 0));
    }

    @Test
    void handoffUniqueIndexTranslatesToDomainException() {
        HandoffDocument document = new HandoffDocument("h1", "s1", "s2", null, null,
                ".workbench/handoff/h1.md", "idem-1", Instant.now());
        handoffRepo.save(document);
        HandoffDocument duplicate = new HandoffDocument("h2", "s1", "s3", null, null,
                ".workbench/handoff/h2.md", "idem-1", Instant.now());
        assertThrows(DuplicateHandoffException.class, () -> handoffRepo.save(duplicate));
        Optional<HandoffDocument> replay = handoffRepo
                .findByFromSessionAndIdempotencyKey("s1", "idem-1");
        assertEquals("h1", replay.orElseThrow().getId());
        // 同会话不同幂等键、不同会话同幂等键均允许
        handoffRepo.save(new HandoffDocument("h3", "s1", "s4", null, null,
                ".workbench/handoff/h3.md", "idem-2", Instant.now()));
        handoffRepo.save(new HandoffDocument("h4", "s9", "s5", null, null,
                ".workbench/handoff/h4.md", "idem-1", Instant.now()));
    }
}
