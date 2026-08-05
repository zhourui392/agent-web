package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.OwnerReference;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Dynamic Stage Run 的幂等提交应用编排。
 *
 * @author alex
 * @since 2026-08-05
 */
@Service
public class WorkbenchStageRunAppService {

    private final WorkbenchStageRunPreparationService preparationService;
    private final WorkbenchStageRunSubmissionCommitter submissionCommitter;

    public WorkbenchStageRunAppService(
            WorkbenchStageRunPreparationService preparationService,
            WorkbenchStageRunSubmissionCommitter submissionCommitter) {
        this.preparationService = Objects.requireNonNull(
                preparationService, "preparationService");
        this.submissionCommitter = Objects.requireNonNull(
                submissionCommitter, "submissionCommitter");
    }

    public WorkbenchStageRunSubmissionResult submit(
            OwnerReference actor, SubmitWorkbenchStageRunCommand command) {
        Optional<WorkbenchStageRunSubmissionResult> replayed =
                submissionCommitter.replayIfPresent(actor, command);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        PreparedWorkbenchStageRun prepared = preparationService.prepare(
                actor, command);
        return submissionCommitter.commit(actor, prepared);
    }
}
