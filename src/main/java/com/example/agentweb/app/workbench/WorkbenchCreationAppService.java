package com.example.agentweb.app.workbench;

import com.example.agentweb.app.workbench.port.WorkspaceScopeGateway;
import com.example.agentweb.app.workbench.port.WorkspaceSnapshotGateway;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.app.agentrun.AgentCatalogService;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchCreationReceipt;
import com.example.agentweb.domain.workbench.WorkbenchCreationRepository;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalogRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Workbench 创建用例：幂等短路、外部事实准备和原子提交编排。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class WorkbenchCreationAppService {

    private static final SnapshotPurpose CREATE_PURPOSE =
            SnapshotPurpose.of("WORKBENCH_CREATE");

    private final WorkbenchCreationRepository creationRepository;
    private final WorkbenchRepository workbenchRepository;
    private final WorkspaceScopeGateway scopeGateway;
    private final WorkspaceSnapshotGateway snapshotGateway;
    private final WorkbenchIdGenerator workbenchIdGenerator;
    private final WorkspaceSnapshotIdGenerator snapshotIdGenerator;
    private final WorkbenchStageCatalogRepository stageCatalogRepository;
    private final WorkbenchStageInstanceIdentifierGenerator
            stageInstanceIdentifierGenerator;
    private final WorkbenchCreationCommitter committer;
    private final AgentCatalogService agentCatalogService;
    private final WorkbenchReleasePolicy releasePolicy;
    private final WorkbenchTelemetry telemetry;
    private final Clock clock;

    public WorkbenchCreationAppService(
            WorkbenchCreationRepository creationRepository,
            WorkbenchRepository workbenchRepository,
            WorkspaceScopeGateway scopeGateway,
            WorkspaceSnapshotGateway snapshotGateway,
            WorkbenchIdGenerator workbenchIdGenerator,
            WorkspaceSnapshotIdGenerator snapshotIdGenerator,
            WorkbenchStageCatalogRepository stageCatalogRepository,
            WorkbenchStageInstanceIdentifierGenerator
                    stageInstanceIdentifierGenerator,
            WorkbenchCreationCommitter committer,
            AgentCatalogService agentCatalogService,
            WorkbenchReleasePolicy releasePolicy,
            WorkbenchTelemetry telemetry, Clock clock) {
        this.creationRepository = creationRepository;
        this.workbenchRepository = workbenchRepository;
        this.scopeGateway = scopeGateway;
        this.snapshotGateway = snapshotGateway;
        this.workbenchIdGenerator = workbenchIdGenerator;
        this.snapshotIdGenerator = snapshotIdGenerator;
        this.stageCatalogRepository = stageCatalogRepository;
        this.stageInstanceIdentifierGenerator = stageInstanceIdentifierGenerator;
        this.committer = committer;
        this.agentCatalogService = agentCatalogService;
        this.releasePolicy = releasePolicy;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public WorkbenchCreationResult create(
            OwnerReference actor, CreateWorkbenchCommand command) {
        WorkbenchCreationResult result;
        try {
            result = executeCreation(actor, command);
        } catch (RuntimeException failure) {
            telemetry.workbenchCreated("FAILED");
            throw failure;
        }
        telemetry.workbenchCreated(
                result.isReplayed() ? "REPLAYED" : "SUCCESS");
        return result;
    }

    private WorkbenchCreationResult executeCreation(
            OwnerReference actor, CreateWorkbenchCommand command) {
        if (actor == null || command == null) {
            throw new IllegalArgumentException(
                    "workbench creation actor and command are required");
        }
        releasePolicy.requireCreationAvailable();
        Optional<WorkbenchCreationReceipt> existing =
                creationRepository.findByOwnerAndIdempotencyKey(
                        actor, command.getIdempotencyKey());
        if (existing.isPresent()) {
            WorkbenchId replayedId = existing.get().requireReplay(
                    actor, command.getIdempotencyKey(), command.getRequestHash());
            return WorkbenchCreationResult.replayed(requireWorkbench(replayedId));
        }

        agentCatalogService.requireWorkbenchAvailable(
                command.getAgentType(), command.getEnvironment());
        WorkbenchStageCatalog stageCatalog = stageCatalogRepository.find();
        List<WorkbenchStageDefinitionRevision> selectedStageRevisions =
                stageCatalog.selectPublishedRevisions(
                        command.getStageDefinitionIdentifiers(),
                        command.getExpectedStageCatalogVersion());
        RepositoryScope scope = scopeGateway.resolve(
                command.getWorkspaceRoot(), command.getRepositorySelection());
        String snapshotId = snapshotIdGenerator.nextId();
        WorkspaceSnapshot snapshot = snapshotGateway.capture(
                snapshotId, scope, CREATE_PURPOSE);
        WorkbenchId workbenchId = workbenchIdGenerator.nextId();
        Instant now = clock.instant();
        Workbench workbench = Workbench.create(
                workbenchId, actor, command.getTitle(), command.getOriginalGoal(),
                command.getAgentType(), command.getEnvironment(), scope,
                snapshot.reference(), stageStates(selectedStageRevisions), now);
        WorkbenchCreationReceipt receipt = WorkbenchCreationReceipt.record(
                actor, command.getIdempotencyKey(), command.getRequestHash(),
                workbenchId, now);
        return committer.commit(
                new PreparedWorkbenchCreation(workbench, snapshot, receipt));
    }

    private List<WorkbenchStageState> stageStates(
            List<WorkbenchStageDefinitionRevision> revisions) {
        List<WorkbenchStageState> stages =
                new ArrayList<WorkbenchStageState>(revisions.size());
        for (WorkbenchStageDefinitionRevision revision : revisions) {
            stages.add(WorkbenchStageState.initial(
                    stageInstanceIdentifierGenerator.nextIdentifier(),
                    WorkbenchStageSnapshot.fromPublishedRevision(revision)));
        }
        return stages;
    }

    private Workbench requireWorkbench(WorkbenchId workbenchId) {
        return workbenchRepository.findById(workbenchId)
                .orElseThrow(() -> new IllegalStateException(
                        "workbench creation receipt points to a missing workbench"));
    }
}
