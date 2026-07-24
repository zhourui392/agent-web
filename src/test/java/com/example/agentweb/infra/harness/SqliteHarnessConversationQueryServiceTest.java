package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessConversationMessageView;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Harness 对话时间线的 SQLite 与 ArtifactStore 投影测试。
 *
 * @author alex
 * @since 2026-07-24
 */
class SqliteHarnessConversationQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T08:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private FileSystemArtifactStore artifactStore;
    private SqliteHarnessConversationQueryService queryService;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:"
                + tempDir.resolve("conversation-query.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        new SqliteInitializer(jdbc).init();
        artifactStore = new FileSystemArtifactStore(tempDir.resolve("artifacts"));
        queryService = new SqliteHarnessConversationQueryService(jdbc, artifactStore);
    }

    @Test
    void should_merge_user_message_and_primary_runtime_artifact_in_time_order() {
        HarnessRun run = HarnessRun.create("run-1", "M4", tempDir.toString(), "CODEX",
                "local", "harness@1", "admin", "create-1",
                StageContract.mvpDefaults(), NOW);
        run.prepareConversationTurn(HarnessStage.ANALYSIS, "message-1",
                "请补充异常路径", "admin", NOW.plusSeconds(1));
        new SqliteHarnessRunRepository(jdbc).add(run);
        insertArtifact("run-1", ArtifactType.REQUIREMENT, "# 修订后的需求", 2L,
                "harness-runtime");
        insertArtifact("run-1", ArtifactType.OPEN_QUESTIONS, "{}", 3L,
                "harness-runtime");
        insertArtifact("run-1", ArtifactType.REQUIREMENT, "# 人工草稿", 4L,
                "admin");

        List<HarnessConversationMessageView> messages = queryService.list("run-1");

        assertEquals(2, messages.size());
        assertEquals("USER", messages.get(0).getRole());
        assertEquals("请补充异常路径", messages.get(0).getContent());
        assertEquals(1, messages.get(0).getAttemptNumber());
        assertEquals("ASSISTANT", messages.get(1).getRole());
        assertEquals("# 修订后的需求", messages.get(1).getContent());
        assertEquals("REQUIREMENT", messages.get(1).getArtifactType());
    }

    @Test
    void should_reject_missing_run_instead_of_returning_ambiguous_empty_timeline() {
        assertThrows(com.example.agentweb.domain.harness.HarnessRunNotFoundException.class,
                () -> queryService.list("missing"));
    }

    private void insertArtifact(String runId, ArtifactType type, String text,
                                long seconds, String createdBy) {
        ArtifactContent content = ArtifactContent.from(text.getBytes(StandardCharsets.UTF_8));
        ArtifactDescriptor descriptor = new ArtifactDescriptor(
                "artifact-" + type.name().toLowerCase() + '-' + seconds,
                type, 1, runId, HarnessStage.ANALYSIS, 1, "text/markdown",
                content.getSizeBytes(), content.getSha256(), ArtifactClassification.INTERNAL,
                createdBy, NOW.plusSeconds(seconds), Collections.<ArtifactReference>emptyList());
        artifactStore.store(descriptor, content);
        jdbc.update("INSERT INTO harness_artifact (run_id, artifact_id, artifact_type, version, "
                        + "stage, attempt_number, content_type, size_bytes, sha256, classification, "
                        + "created_by, created_at, source_artifacts_json) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                descriptor.getRunId(), descriptor.getArtifactId(), descriptor.getArtifactType().name(),
                descriptor.getVersion(), descriptor.getStage().name(), descriptor.getAttempt(),
                descriptor.getContentType(), descriptor.getSizeBytes(), descriptor.getSha256(),
                descriptor.getClassification().name(), descriptor.getCreatedBy(),
                descriptor.getCreatedAt().toEpochMilli(), "[]");
    }
}
