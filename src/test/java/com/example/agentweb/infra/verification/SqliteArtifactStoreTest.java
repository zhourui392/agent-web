package com.example.agentweb.infra.verification;

import com.example.agentweb.adapter.verification.CollectedArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqliteArtifactStore 轻集成(@TempDir + SQLiteDataSource,不起 Spring):
 * saveAll/findByRequirementId 往返、requirement 间隔离、空列表不抛。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteArtifactStoreTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteArtifactStore store;

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("artifact-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        // 照 schema.sql 的 requirement_artifact 定义建表
        jdbc.execute("CREATE TABLE IF NOT EXISTS requirement_artifact ("
                + "id             INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "requirement_id TEXT    NOT NULL,"
                + "kind           TEXT    NOT NULL,"
                + "content        TEXT,"
                + "file_path      TEXT,"
                + "created_at     INTEGER NOT NULL)");
        store = new SqliteArtifactStore(jdbc);
    }

    @Test
    public void saveAll_then_findByRequirementId_should_round_trip_in_insert_order() {
        // Given: 一条内联工件 + 一条超限落盘工件
        List<CollectedArtifact> artifacts = List.of(
                new CollectedArtifact("FLOWSTATE", "state: SWIMLANE_VERIFIED", null),
                new CollectedArtifact("FAILED_CASES", null, "D:/worktree/failed_cases.json"));
        Instant createdAt = Instant.parse("2026-07-04T10:00:00Z");

        // When
        store.saveAll("R-1", artifacts, createdAt);
        List<CollectedArtifact> loaded = store.findByRequirementId("R-1");

        // Then: 按落库顺序返回,content/filePath 空值原样往返
        assertEquals(2, loaded.size());
        assertEquals("FLOWSTATE", loaded.get(0).getKind());
        assertEquals("state: SWIMLANE_VERIFIED", loaded.get(0).getContent());
        assertNull(loaded.get(0).getFilePath());
        assertEquals("FAILED_CASES", loaded.get(1).getKind());
        assertNull(loaded.get(1).getContent());
        assertEquals("D:/worktree/failed_cases.json", loaded.get(1).getFilePath());
    }

    @Test
    public void saveAll_should_persist_created_at_as_epoch_millis() {
        // Given
        Instant createdAt = Instant.parse("2026-07-04T10:00:00Z");

        // When
        store.saveAll("R-2", List.of(new CollectedArtifact("FLOWSTATE", "x", null)), createdAt);

        // Then: 时间列存 epoch millis
        Long persisted = jdbc.queryForObject(
                "SELECT created_at FROM requirement_artifact WHERE requirement_id=?", Long.class, "R-2");
        assertEquals(createdAt.toEpochMilli(), persisted);
    }

    @Test
    public void findByRequirementId_should_isolate_different_requirements() {
        // Given: 两个需求各自落库
        Instant now = Instant.parse("2026-07-04T10:00:00Z");
        store.saveAll("R-a", List.of(new CollectedArtifact("FLOWSTATE", "a", null)), now);
        store.saveAll("R-b", List.of(
                new CollectedArtifact("FLOWSTATE", "b1", null),
                new CollectedArtifact("FAILED_CASES", "b2", null)), now);

        // When
        List<CollectedArtifact> loadedA = store.findByRequirementId("R-a");
        List<CollectedArtifact> loadedB = store.findByRequirementId("R-b");

        // Then: 互不串数据
        assertEquals(1, loadedA.size());
        assertEquals("a", loadedA.get(0).getContent());
        assertEquals(2, loadedB.size());
    }

    @Test
    public void saveAll_with_empty_list_should_not_throw() {
        // Given: 空列表与 null 列表
        Instant now = Instant.parse("2026-07-04T10:00:00Z");

        // When & Then: 不抛异常且不产生记录
        assertDoesNotThrow(() -> store.saveAll("R-empty", Collections.emptyList(), now));
        assertDoesNotThrow(() -> store.saveAll("R-empty", null, now));
        assertTrue(store.findByRequirementId("R-empty").isEmpty());
    }

    @Test
    public void findByRequirementId_miss_should_return_empty_list() {
        // When & Then
        assertTrue(store.findByRequirementId("R-not-exist").isEmpty());
    }
}
