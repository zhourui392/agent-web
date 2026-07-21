package com.example.agentweb;

import com.example.agentweb.app.requirement.RequirementAppService;
import com.example.agentweb.app.workspace.WorkspaceAppService;
import com.example.agentweb.app.workspace.WorkspaceCleanupService;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.support.GitRepoFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全栈锚点（verification-plan §五预算第 1 条）：平台 @Scheduled 装配 + 一轮 TTL tick
 * 走到真实 git 释放（CLI 无关，git fixture）。其余清理分支逻辑在 WorkspaceCleanupServiceTest 单测。
 * 放根测试包与其他 @SpringBootTest 锚点同侧（TestCliStub 包私有）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@SpringBootTest
public class WorkspaceCleanupSchedulingTest {

    /** 不用 @TempDir：其静态注入与 @DynamicPropertySource 的上下文创建时序无保障。 */
    private static final Path TEMP_ROOT = createTempRoot();

    private static GitRepoFixture fixture;

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("workspace-anchor");
        } catch (IOException e) {
            throw new IllegalStateException("创建锚点测试临时目录失败", e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        TestCliStub.register(registry);
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEMP_ROOT.resolve("anchor.db").toAbsolutePath());
        registry.add("agent.requirement.enabled", () -> "true");
        registry.add("agent.requirement.workspace.root",
                () -> TEMP_ROOT.resolve("ws-root").toString());
        registry.add("agent.requirement.workspace.repo-url",
                WorkspaceCleanupSchedulingTest::remoteRepoUrl);
    }

    private static synchronized String remoteRepoUrl() {
        if (fixture == null) {
            fixture = GitRepoFixture.createBare(TEMP_ROOT.resolve("origin.git"))
                    .withCommit("README.md", "seed", "init");
        }
        return fixture.url();
    }

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledProcessor;
    @Autowired
    private RequirementAppService requirementAppService;
    @Autowired
    private WorkspaceAppService workspaceAppService;
    @Autowired
    private WorkspaceCleanupService cleanupService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    public void cleanup_cron_should_be_registered_as_scheduled_task() {
        boolean registered = scheduledProcessor.getScheduledTasks().stream()
                .map(task -> task.getTask().toString())
                .anyMatch(s -> s.contains("WorkspaceCleanupService.cleanupExpired"));

        assertTrue(registered, "TTL 清理未注册进 @Scheduled");
    }

    @Test
    public void one_tick_should_release_expired_clean_workspace() {
        String owner = "V33215020";
        String requirementId = requirementAppService.create("锚点需求", null, owner, RequirementSource.BOARD);
        requirementAppService.attachPlan(requirementId, "1. 验证清理", owner);
        requirementAppService.approve(requirementId, owner);
        String workspaceId = workspaceAppService.provisionFor(requirementId);
        String worktreePath = jdbc.queryForObject(
                "SELECT worktree_path FROM requirement_workspace WHERE id=?", String.class, workspaceId);
        assertTrue(Files.isDirectory(Paths.get(worktreePath)));

        long agedEpoch = Instant.now().minus(Duration.ofHours(100)).toEpochMilli();
        jdbc.update("UPDATE requirement_workspace SET last_active_at=? WHERE id=?",
                agedEpoch, workspaceId);

        cleanupService.cleanupExpired();

        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT status FROM requirement_workspace WHERE id=?", String.class, workspaceId));
        assertTrue(jdbc.queryForList("SELECT port FROM port_lease WHERE workspace_id=?",
                Integer.class, workspaceId).isEmpty());
        assertTrue(Files.notExists(Paths.get(worktreePath)), "worktree 应随释放删除");
    }
}
