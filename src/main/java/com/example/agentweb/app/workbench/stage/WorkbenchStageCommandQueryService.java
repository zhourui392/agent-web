package com.example.agentweb.app.workbench.stage;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCapabilityResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 从 Workbench 内冻结 Stage Snapshot 查询可调用 Slash Command。
 *
 * @author alex
 * @since 2026-08-05
 */
@Service
@Transactional(readOnly = true)
public class WorkbenchStageCommandQueryService {

    private final WorkbenchRepository workbenchRepository;
    private final WorkbenchStageCapabilityResolver capabilityResolver;

    public WorkbenchStageCommandQueryService(
            WorkbenchRepository workbenchRepository,
            WorkbenchStageCapabilityResolver capabilityResolver) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.capabilityResolver = Objects.requireNonNull(
                capabilityResolver, "capabilityResolver");
    }

    public List<WorkbenchStageCommandView> list(
            OwnerReference actor, WorkbenchId workbenchId,
            String stageInstanceIdentifier) {
        if (actor == null || workbenchId == null) {
            throw new IllegalArgumentException(
                    "Stage Command actor and Workbench are required");
        }
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
        requireVisibleStage(workbench, actor, stageInstanceIdentifier);
        List<CommandDefinition> commands = capabilityResolver.listCommands(
                workbench.stage(stageInstanceIdentifier).getSnapshot());
        List<WorkbenchStageCommandView> views =
                new ArrayList<WorkbenchStageCommandView>(commands.size());
        for (CommandDefinition command : commands) {
            views.add(WorkbenchStageCommandView.from(command));
        }
        return Collections.unmodifiableList(views);
    }

    private void requireVisibleStage(
            Workbench workbench, OwnerReference actor,
            String stageInstanceIdentifier) {
        try {
            workbench.requireOwnedBy(actor);
            workbench.stage(stageInstanceIdentifier);
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() == WorkbenchErrorCode.OWNER_REQUIRED
                    || failure.getCode()
                    == WorkbenchErrorCode.STAGE_NOT_FOUND) {
                throw new WorkbenchNotFoundException();
            }
            throw failure;
        }
    }
}
