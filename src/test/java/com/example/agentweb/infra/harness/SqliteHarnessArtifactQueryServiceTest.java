package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessArtifactContentView;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Artifact 下载 CQRS 投影的真实 SQLite 与受控文件存储测试。
 *
 * @author alex
 * @since 2026-07-24
 */
class SqliteHarnessArtifactQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private FileSystemArtifactStore artifactStore;
    private SqliteHarnessArtifactQueryService queryService;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("artifact-query.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        new SqliteInitializer(jdbc).init();
        SqliteHarnessRunRepository runRepository = new SqliteHarnessRunRepository(jdbc);
        runRepository.add(HarnessRun.create("run-1", "M4", tempDir.toString(), "CODEX",
                "local", "harness@1", "admin", "create-1", StageContract.mvpDefaults(), NOW));
        runRepository.add(HarnessRun.create("run-2", "M4", tempDir.toString(), "CODEX",
                "local", "harness@1", "admin", "create-2", StageContract.mvpDefaults(), NOW));
        artifactStore = new FileSystemArtifactStore(tempDir.resolve("artifacts"));
        queryService = new SqliteHarnessArtifactQueryService(jdbc, artifactStore);
    }

    @Test
    void shouldReadLatestLogicalVersionAndFinalReportWithoutCrossingRunBoundary() {
        ArtifactDescriptor versionOne = insert("run-1", "../../requirements",
                ArtifactType.REQUIREMENT, 1, "first");
        ArtifactDescriptor versionTwo = insert("run-1", "../../requirements",
                ArtifactType.REQUIREMENT, 2, "second");
        ArtifactDescriptor otherRun = insert("run-2", "../../requirements",
                ArtifactType.REQUIREMENT, 3, "other-run");
        ArtifactDescriptor report = insert("run-1", "final-report",
                ArtifactType.FINAL_REPORT, 1, "# Delivery complete");

        HarnessArtifactContentView latest = queryService.findLatest(
                "run-1", "../../requirements").orElseThrow(AssertionError::new);
        HarnessArtifactContentView finalReport = queryService.findFinalReport("run-1")
                .orElseThrow(AssertionError::new);

        assertEquals(versionTwo.getArtifactId(), latest.getArtifactId());
        assertEquals(2, latest.getVersion());
        assertEquals(versionTwo.getSha256(), latest.getSha256());
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), latest.getContent());
        assertEquals(report.getSha256(), finalReport.getSha256());
        assertArrayEquals("# Delivery complete".getBytes(StandardCharsets.UTF_8),
                finalReport.getContent());
        assertEquals(otherRun.getSha256(), queryService.findLatest(
                "run-2", "../../requirements").orElseThrow(AssertionError::new).getSha256());
        assertFalse(queryService.findLatest("run-1", "missing").isPresent());
        assertFalse(queryService.findFinalReport("run-2").isPresent());
        assertFalse(versionOne.getSha256().equals(latest.getSha256()));
    }

    private ArtifactDescriptor insert(String runId, String artifactId, ArtifactType type,
                                      int version, String text) {
        ArtifactContent content = ArtifactContent.from(text.getBytes(StandardCharsets.UTF_8));
        ArtifactDescriptor descriptor = new ArtifactDescriptor(artifactId, type, version,
                runId, type == ArtifactType.FINAL_REPORT ? HarnessStage.DEPLOYMENT
                : HarnessStage.ANALYSIS, 1, type == ArtifactType.FINAL_REPORT
                ? "text/markdown" : "text/plain", content.getSizeBytes(), content.getSha256(),
                ArtifactClassification.INTERNAL, "harness", NOW.plusSeconds(version),
                Collections.<ArtifactReference>emptyList());
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
        return descriptor;
    }
}
