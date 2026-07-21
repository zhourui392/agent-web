package com.example.agentweb.infra.requirement;

import com.example.agentweb.app.requirement.RequirementBoardItem;
import com.example.agentweb.app.requirement.RequirementDetail;
import com.example.agentweb.app.requirement.RequirementEventView;
import com.example.agentweb.domain.requirement.AgentPlan;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementId;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读侧投影轻集成：过滤、DTO 字段映射、countActiveByOwner 只数非终态。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteRequirementQueryServiceTest {

    private static final String OWNER = "V33215020";

    @TempDir
    Path tempDir;

    private SqliteRequirementRepo repo;
    private SqliteRequirementQueryService queryService;

    @BeforeEach
    public void setUp() {
        File dbFile = tempDir.resolve("requirement-query-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        RequirementTestSchema.create(jdbc);
        repo = new SqliteRequirementRepo(jdbc);
        queryService = new SqliteRequirementQueryService(jdbc);
    }

    /**
     * 确定性 ID 建聚合（全量重建构造器）：newId() 是日期+4 位随机数，同库多聚合用例
     * 存在生日碰撞 flake（全量回归已实际撞出过 PK 冲突），多建必须走这里。
     */
    private Requirement withId(String id, String title, String owner) {
        return new Requirement(new RequirementId(id), RequirementSource.BOARD, null, title, "d",
                RequirementStatus.INTAKE, null, null, owner, List.of(), null,
                Instant.now(), Instant.now());
    }

    @Test
    public void listBoard_should_map_fields_and_flags() {
        Requirement planned = withId("RT000000001", "有计划", OWNER);
        planned.attachPlan(new AgentPlan("p", null, null, Instant.now()), OWNER);
        repo.save(planned);
        repo.save(withId("RT000000002", "无计划", OWNER));

        List<RequirementBoardItem> items = queryService.listBoard(null, null);

        assertEquals(2, items.size());
        RequirementBoardItem withPlan = items.stream()
                .filter(i -> "有计划".equals(i.title())).findFirst().orElseThrow();
        assertTrue(withPlan.planAttached());
        assertFalse(withPlan.workspaceAttached());
        assertEquals("PLANNED", withPlan.status());
        assertEquals(OWNER, withPlan.owner());
    }

    @Test
    public void listBoard_should_filter_by_status_and_owner() {
        repo.save(withId("RT000000001", "mine", OWNER));
        repo.save(withId("RT000000002", "others", "V99999999"));

        assertEquals(1, queryService.listBoard(null, OWNER).size());
        assertEquals(2, queryService.listBoard("INTAKE", null).size());
        assertEquals(0, queryService.listBoard("PLANNED", null).size());
    }

    @Test
    public void getDetail_should_expose_plan_and_participants() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "标题", "描述", OWNER);
        requirement.addParticipant("V88888888");
        requirement.attachPlan(new AgentPlan("计划正文", null, null, Instant.now()), OWNER);
        repo.save(requirement);

        RequirementDetail detail = queryService.getDetail(requirement.getId().getValue());

        assertEquals("标题", detail.title());
        assertEquals("PLANNED", detail.status());
        assertEquals("计划正文", detail.planText());
        assertEquals(List.of("V88888888"), detail.participants());
        assertNull(queryService.getDetail("R-not-exist"));
    }

    @Test
    public void listEvents_should_return_timeline_in_order() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", OWNER);
        requirement.attachPlan(new AgentPlan("p", null, null, Instant.now()), OWNER);
        repo.save(requirement);

        List<RequirementEventView> events = queryService.listEvents(requirement.getId().getValue());

        assertEquals(2, events.size());
        assertEquals("CREATED", events.get(0).eventType());
        assertEquals("PLAN_ATTACHED", events.get(1).eventType());
        assertEquals("INTAKE", events.get(1).fromStatus());
        assertEquals("PLANNED", events.get(1).toStatus());
    }

    @Test
    public void searchEvents_should_filter_by_actor_and_time_window_desc() {
        Requirement mine = withId("RT000000001", "a", OWNER);
        mine.suspend(OWNER, "hold");
        repo.save(mine);
        Requirement others = withId("RT000000002", "b", "V99999999");
        others.archive("V99999999", "done");
        repo.save(others);

        List<com.example.agentweb.app.requirement.RequirementEventSearchItem> byActor =
                queryService.searchEvents(OWNER, null, null, 10);
        // withId 全量重建构造器不产 CREATED 事件,故 OWNER 只有 SUSPENDED 一条
        assertEquals(1, byActor.size());
        assertEquals("SUSPENDED", byActor.get(0).eventType());
        assertEquals("RT000000001", byActor.get(0).requirementId());

        // 时间窗:未来窗口查不到
        long future = Instant.now().plusSeconds(3600).toEpochMilli();
        assertTrue(queryService.searchEvents(null, future, null, 10).isEmpty());

        // 全量倒序 + limit
        List<com.example.agentweb.app.requirement.RequirementEventSearchItem> all =
                queryService.searchEvents(null, null, null, 1);
        assertEquals(1, all.size());
        assertEquals("ARCHIVED", all.get(0).eventType());
    }

    @Test
    public void countActiveByOwner_should_exclude_terminal_status() {
        repo.save(withId("RT000000001", "active", OWNER));
        Requirement archived = withId("RT000000002", "archived", OWNER);
        archived.archive(OWNER, "done");
        repo.save(archived);
        Requirement suspended = withId("RT000000003", "suspended", OWNER);
        suspended.suspend(OWNER, "hold");
        repo.save(suspended);

        // SUSPENDED 计入活跃, ARCHIVED 不计
        assertEquals(2, queryService.countActiveByOwner(OWNER));
        assertEquals(0, queryService.countActiveByOwner("V00000000"));
    }
}
