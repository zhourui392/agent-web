package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.HandoffSnapshotReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityOverridePolicy;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
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
import java.util.HashSet;

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
                workspace.scope, workspace.snapshot.reference(), NOW.plusMillis(30));
    }

    static WorkbenchRunSnapshot reviewRunSnapshot(
            Workbench workbench, WorkspaceSnapshot workspaceSnapshot,
            ReviewModifyConfirmation confirmation, String runId) {
        return reviewRunSnapshot(
                workbench, workspaceSnapshot, confirmation, runId,
                "submission-" + runId, HASH_F);
    }

    static WorkbenchRunSnapshot reviewRunSnapshot(
            Workbench workbench, WorkspaceSnapshot workspaceSnapshot,
            ReviewModifyConfirmation confirmation, String runId,
            String submissionIdempotencyKey, String submissionRequestHash) {
        return WorkbenchRunSnapshot.create(
                runId, workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR,
                submissionIdempotencyKey, submissionRequestHash,
                RunMode.MODIFY_WORKSPACE, workbench.getRepositoryScope(),
                workspaceSnapshot.reference(), capabilityBinding(), Long.valueOf(3L),
                HandoffSnapshotReference.of(
                        WorkbenchPhase.IMPLEMENT_TEST, 2L, HASH_B),
                Arrays.asList(
                        PromptPartSnapshot.of("HANDOFF", "phase-handoff", HASH_C, 128),
                        PromptPartSnapshot.of("USER_INPUT", "owner", HASH_D, 64)),
                HASH_E,
                RuntimeEnforcementSnapshot.modify(
                        "CODEX", "0.42.0", workbench.getRepositoryScope().getScopeHash(),
                        "agent-web", Arrays.asList("service-api", "agent-web"),
                        1800L, 8_388_608L),
                confirmation, NOW.plusSeconds(10));
    }

    static ResolvedCapabilityBinding capabilityBinding() {
        return ResolvedCapabilityBinding.resolve(
                "policy@1", "review-profile", "3", HASH_A,
                Arrays.asList(
                        new ResolvedRuleBinding(
                                "platform/safety", "1", "PLATFORM", HASH_B,
                                true, "强制安全规则"),
                        new ResolvedRuleBinding(
                                "review/style", "2", "WORKSPACE", HASH_C,
                                false, "Review 风格")),
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

    static ReviewOpinion reviewOpinion(Workbench workbench) {
        return ReviewOpinion.record(
                workbench.getId(), 2L, HASH_F, OWNER, NOW.plusSeconds(7));
    }

    static ReviewModifyConfirmation reviewConfirmation(Workbench workbench) {
        return ReviewModifyConfirmation.confirm(
                "confirmation-1", reviewOpinion(workbench), OWNER, NOW.plusSeconds(8));
    }

    static PhaseCapabilityConfiguration capabilityConfiguration(Workbench workbench) {
        return PhaseCapabilityConfiguration.create(
                workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR,
                "review-profile", "3",
                CapabilityOverride.of(
                        Collections.singleton("refactor-assistant"),
                        Collections.singleton("optional-linter"),
                        Collections.singleton("repository-query"),
                        Collections.singleton("review/style")),
                capabilityPolicy(), OWNER, NOW.plusSeconds(5));
    }

    static PhaseCapabilityOverridePolicy capabilityPolicy() {
        return PhaseCapabilityOverridePolicy.constrainedTo(
                WorkbenchPhase.REVIEW_REFACTOR,
                new HashSet<String>(Arrays.asList(
                        "refactor-assistant", "optional-linter")),
                Collections.singleton("repository-query"),
                Collections.singleton("review/style"),
                Collections.singleton("platform/safety"));
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
