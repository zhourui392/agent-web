package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import com.example.agentweb.infra.SqliteInitializer;
import com.example.agentweb.infra.workspace.SqliteWorkspaceSnapshotRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

/**
 * Workbench SQLite 测试的领域夹具，只创建真实领域对象，不绕过不变量。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkbenchPersistenceFixtures {

    static final Instant NOW = Instant.parse("2026-08-01T08:00:00.123Z");
    static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");
    static final OwnerReference OWNER_2 = OwnerReference.of("user-2", "Taylor");
    static final String HASH_A = repeat('a');
    static final String HASH_B = repeat('b');
    static final String HASH_C = repeat('c');
    static final String HASH_D = repeat('d');
    static final String HASH_E = repeat('e');
    static final String HASH_F = repeat('f');
    static final String HEAD_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final String HEAD_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private WorkbenchPersistenceFixtures() {
    }

    static JdbcTemplate initializedJdbc(Path databasePath) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + databasePath.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        return jdbc;
    }

    static WorkspaceFixture persistWorkspace(JdbcTemplate jdbc, Path tempDir,
                                             String snapshotId) {
        String workspaceRoot = tempDir.resolve("workspace").toAbsolutePath()
                .normalize().toString();
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Arrays.asList("service-api", "agent-web"));
        RepositoryScope scope = RepositoryScope.create(
                workspaceRoot, selection,
                Arrays.asList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", Paths.get(workspaceRoot, "agent-web").toString(),
                                HASH_A, false),
                        ResolvedRepository.fromVerifiedFacts(
                                "service-api", Paths.get(workspaceRoot, "service-api").toString(),
                                HASH_B, false)),
                8);
        WorkspaceTopology topology = WorkspaceTopology.of(workspaceRoot, selection);
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(
                snapshotId, SnapshotPurpose.of("WORKBENCH_CREATE"), topology,
                Arrays.asList(
                        RepositoryBaseline.capture(
                                "agent-web", Paths.get(workspaceRoot, "agent-web").toString(),
                                "feature/workbench", HEAD_A, true, HASH_C, NOW),
                        RepositoryBaseline.capture(
                                "service-api", Paths.get(workspaceRoot, "service-api").toString(),
                                "main", HEAD_B, true, HASH_D, NOW.plusMillis(10))),
                Collections.emptyList(), NOW.minusMillis(10), NOW.plusMillis(20));
        new SqliteWorkspaceSnapshotRepository(jdbc).add(snapshot);
        return new WorkspaceFixture(scope, snapshot);
    }

    static Workbench newWorkbench(WorkspaceFixture workspace, String workbenchId) {
        return Workbench.create(
                WorkbenchId.of(workbenchId), OWNER, "Local Workbench",
                "实现多仓库本地开发工作台", AgentType.CODEX, "local",
                workspace.scope, workspace.snapshot.reference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        "stage-default", defaultStageSnapshot())),
                NOW.plusMillis(30));
    }

    static ResolvedCapabilityBinding capabilityBinding() {
        return ResolvedCapabilityBinding.resolve(
                "policy@1", "stage-profile", "3", HASH_A,
                Arrays.asList(
                        new ResolvedRuleBinding(
                                "platform/safety", "1", "PLATFORM", HASH_B,
                                true, "强制安全规则"),
                        new ResolvedRuleBinding(
                                "stage/style", "2", "WORKSPACE", HASH_C,
                                false, "Stage 风格")),
                Collections.singletonList(new ResolvedSkillBinding(
                        "refactor-assistant", "4", "APPROVED_USER", HASH_D,
                        "APPROVED")),
                Collections.singletonList(new ResolvedMcpServerBinding(
                        "repository-query", "2", HASH_E, CapabilityAccess.READ,
                        "STDIO")),
                Collections.singletonList(new RejectedCapability(
                        "write-production", "ACCESS_FORBIDDEN")),
                "CODEX");
    }

    private static WorkbenchStageSnapshot defaultStageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor editor = StageCatalogEditor.create(
                "admin-1", "Admin");
        catalog.createDraft(
                "default-stage",
                WorkbenchStageDraftContent.create(
                        10, "默认阶段", "测试阶段", "遵守工作区边界",
                        Collections.singleton(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                editor, NOW);
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                "default-stage", catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                editor, NOW.plusMillis(1));
        return WorkbenchStageSnapshot.fromPublishedRevision(revision);
    }

    static String repeat(char character) {
        return String.join("", Collections.nCopies(64, String.valueOf(character)));
    }

    static final class WorkspaceFixture {
        private final RepositoryScope scope;
        private final WorkspaceSnapshot snapshot;

        private WorkspaceFixture(RepositoryScope scope, WorkspaceSnapshot snapshot) {
            this.scope = scope;
            this.snapshot = snapshot;
        }

        RepositoryScope scope() {
            return scope;
        }

        WorkspaceSnapshot snapshot() {
            return snapshot;
        }
    }
}
