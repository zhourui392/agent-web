package com.example.agentweb.infra.requirement;

import com.example.agentweb.domain.requirement.AgentPlan;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.domain.requirement.RequirementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写侧仓储轻集成（@TempDir + SQLiteDataSource，不起 Spring）：save/find 回环全字段、
 * JSON 编解码、事件仅追加不回读进聚合。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteRequirementRepoTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteRequirementRepo repo;

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("requirement-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        RequirementTestSchema.create(jdbc);
        repo = new SqliteRequirementRepo(jdbc);
    }

    @Test
    public void save_then_findById_should_round_trip_all_fields() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "标题", "描述", "V33215020");
        requirement.addParticipant("V88888888");
        requirement.attachPlan(new AgentPlan("计划正文", "hash1", "run-1", Instant.now()), "V33215020");

        repo.save(requirement);
        Requirement loaded = repo.findById(requirement.getId().getValue());

        assertNotNull(loaded);
        assertEquals("标题", loaded.getTitle());
        assertEquals("描述", loaded.getDescription());
        assertEquals(RequirementStatus.PLANNED, loaded.getStatus());
        assertEquals(RequirementSource.BOARD, loaded.getSource());
        assertEquals("V33215020", loaded.getOwner());
        assertEquals(List.of("V88888888"), loaded.getParticipants());
        assertEquals("计划正文", loaded.getPlan().getPlanText());
        assertEquals("hash1", loaded.getPlan().getPromptHash());
        assertEquals("run-1", loaded.getPlan().getSourceRunId());
        // 重建的聚合事件缓冲为空: 事件仅追加落库, 不回读进聚合
        assertTrue(loaded.pullEvents().isEmpty());
    }

    @Test
    public void findById_miss_should_return_null() {
        assertNull(repo.findById("R-not-exist"));
    }

    @Test
    public void save_should_flush_pending_events_into_event_table() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", "V33215020");
        requirement.attachPlan(new AgentPlan("p", null, null, Instant.now()), "V33215020");

        repo.save(requirement);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_type, actor FROM requirement_event WHERE requirement_id=? ORDER BY id",
                requirement.getId().getValue());
        assertEquals(2, rows.size());
        assertEquals("CREATED", rows.get(0).get("event_type"));
        assertEquals("PLAN_ATTACHED", rows.get(1).get("event_type"));
    }

    @Test
    public void update_should_persist_transition_and_append_events() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", "V33215020");
        requirement.attachPlan(new AgentPlan("p", null, null, Instant.now()), "V33215020");
        repo.save(requirement);

        requirement.approve("V33215020");
        requirement.suspend("V33215020", "等依赖");
        repo.update(requirement);

        Requirement loaded = repo.findById(requirement.getId().getValue());
        assertEquals(RequirementStatus.SUSPENDED, loaded.getStatus());
        assertEquals(RequirementStatus.APPROVED, loaded.getStatusBeforeSuspend());
        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM requirement_event WHERE requirement_id=?",
                Integer.class, requirement.getId().getValue());
        assertEquals(4, eventCount);
    }

    @Test
    public void null_optional_fields_should_round_trip() {
        Requirement requirement = Requirement.create(RequirementSource.REST_API, "t", null, "V33215020");

        repo.save(requirement);
        Requirement loaded = repo.findById(requirement.getId().getValue());

        assertNull(loaded.getDescription());
        assertNull(loaded.getPlan());
        assertNull(loaded.getWorkspaceId());
        assertNull(loaded.getStatusBeforeSuspend());
        assertTrue(loaded.getParticipants().isEmpty());
    }
}
